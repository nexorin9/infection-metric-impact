package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.metriclib.MetricDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 复算作业队列与并发隔离（任务36）。
 *
 * <p>一次复算 = 指标集合 × 季度集合；每项作业独立记录状态（待跑 / 成功 / 失败 / 未跑）与耗时。
 * 季度间并行（线程池默认 4、可配），指标间串行以保证结果可复现；
 * 单项失败不中断其它项，失败进运行摘要带可读原因、不得按 0 计入分母。</p>
 *
 * <p>取消与进度：可报告已完成 / 总数；取消后已落盘（成功）结果保留，未完成项标为
 * 未跑且不写半截结果。collector 只在主线程串行写摘要，避免并行写导致结果不确定。</p>
 */
public final class RecomputeQueue {

    public enum JobStatus {
        PENDING("待跑"), SUCCESS("成功"), FAILED("失败"), NOT_RUN("未跑");

        private final String cn;
        JobStatus(String cn) { this.cn = cn; }
        public String getCn() { return cn; }
    }

    /** 单项复算作业执行器（测试注入点，可注入失败作业或返回失败结果）。 */
    @FunctionalInterface
    public interface RecomputeTask {
        RecomputeResult execute(MetricDefinition def, String quarter) throws Exception;
    }

    /** 单项作业状态与耗时。 */
    public static final class Job {
        private final String indicatorCode;
        private final String quarter;
        private final String version;
        private JobStatus status = JobStatus.PENDING;
        private long elapsedMillis;
        private RecomputeResult result;
        private String reason = "";

        Job(String indicatorCode, String quarter, String version) {
            this.indicatorCode = indicatorCode;
            this.quarter = quarter;
            this.version = version;
        }

        void setStatus(JobStatus s) { this.status = s; }
        void setElapsedMillis(long m) { this.elapsedMillis = m; }
        void setResult(RecomputeResult r) { this.result = r; }
        void setReason(String r) { this.reason = r == null ? "" : r; }

        public String getIndicatorCode() { return indicatorCode; }
        public String getQuarter() { return quarter; }
        public String getVersion() { return version; }
        public JobStatus getStatus() { return status; }
        public long getElapsedMillis() { return elapsedMillis; }
        public RecomputeResult getResult() { return result; }
        public String getReason() { return reason; }

        public ObjectNode toJson() {
            ObjectNode o = JsonSupport.object();
            o.put("indicator_code", indicatorCode);
            o.put("quarter", quarter);
            o.put("definition_version", version);
            o.put("status", status.name());
            o.put("status_cn", status.getCn());
            o.put("elapsed_millis", elapsedMillis);
            if (result == null) o.putNull("result"); else o.set("result", result.toJson());
            o.put("reason", reason);
            return o;
        }
    }

    /** 一次队列运行的汇总结果。 */
    public static final class QueueResult {
        private final List<Job> jobs;
        private final int total;
        private final int completed;
        private final int successCount;
        private final int failedCount;
        private final int notRunCount;
        private final boolean cancelled;
        private final RunSummary summary;

        QueueResult(List<Job> jobs, int total, int completed, int successCount,
                    int failedCount, int notRunCount, boolean cancelled, RunSummary summary) {
            this.jobs = List.copyOf(jobs);
            this.total = total;
            this.completed = completed;
            this.successCount = successCount;
            this.failedCount = failedCount;
            this.notRunCount = notRunCount;
            this.cancelled = cancelled;
            this.summary = summary;
        }

        public List<Job> getJobs() { return jobs; }
        public int getTotal() { return total; }
        public int getCompleted() { return completed; }
        public int getSuccessCount() { return successCount; }
        public int getFailedCount() { return failedCount; }
        public int getNotRunCount() { return notRunCount; }
        public boolean isCancelled() { return cancelled; }
        public RunSummary getSummary() { return summary; }

        public String progressText() { return completed + "/" + total; }

        public ObjectNode toJson() {
            ObjectNode o = JsonSupport.object();
            o.put("total", total);
            o.put("completed", completed);
            o.put("success", successCount);
            o.put("failed", failedCount);
            o.put("not_run", notRunCount);
            o.put("cancelled", cancelled);
            o.put("progress", progressText());
            ArrayNode arr = o.putArray("jobs");
            for (Job j : jobs) arr.add(j.toJson());
            return o;
        }
    }

    private final int threads;
    private volatile boolean cancelled;
    private final AtomicInteger completed = new AtomicInteger();
    private volatile int total;

    public RecomputeQueue() { this(4); }

    public RecomputeQueue(int threads) {
        if (threads < 1) {
            throw new AppException(ErrorCodes.E_RECOMPUTE_001, "季度并行线程数必须 ≥ 1，实际 " + threads);
        }
        this.threads = threads;
    }

    /** 请求取消：后续未开始作业标为未跑，已成功结果保留。 */
    public void cancel() { this.cancelled = true; }

    public boolean isCancelled() { return cancelled; }

    public String progressText() { return completed.get() + "/" + total; }

    public double progress() { return total == 0 ? 0.0 : (double) completed.get() / total; }

    /**
     * 运行一次复算：指标集合 × 季度集合。季度间并行、指标间串行。
     */
    public QueueResult run(List<MetricDefinition> defs, List<String> quarters, RecomputeTask task) {
        if (defs == null || quarters == null || task == null) {
            throw new AppException(ErrorCodes.E_RECOMPUTE_001,
                    "复算队列输入不可为空：指标集合 / 季度集合 / 作业执行器");
        }
        cancelled = false;
        total = defs.size() * quarters.size();
        completed.set(0);

        Map<String, List<Job>> byQuarter = new LinkedHashMap<>();
        for (String q : quarters) {
            List<Job> jobs = new ArrayList<>();
            for (MetricDefinition d : defs) {
                jobs.add(new Job(d.getIndicatorCode(), q, d.getDefinitionVersion()));
            }
            byQuarter.put(q, jobs);
        }

        int poolSize = Math.min(threads, Math.max(1, byQuarter.size()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<Future<List<Job>>> futures = new ArrayList<>();
        try {
            for (Map.Entry<String, List<Job>> e : byQuarter.entrySet()) {
                final String quarter = e.getKey();
                final List<Job> jobs = e.getValue();
                final List<MetricDefinition> defsRef = defs;
                final RecomputeTask taskRef = task;
                futures.add(pool.submit(() -> runQuarter(jobs, quarter, defsRef, taskRef)));
            }

            List<Job> all = new ArrayList<>();
            for (Future<List<Job>> f : futures) {
                try {
                    all.addAll(f.get());
                } catch (Exception ex) {
                    throw new AppException(ErrorCodes.E_RECOMPUTE_001,
                            "复算作业线程异常：" + describe(ex));
                }
            }
            // 稳定排序，保证并发与串行结果逐项一致
            all.sort(Comparator.comparing((Job j) -> j.getQuarter())
                    .thenComparing(j -> j.getIndicatorCode()));

            // 主线程串行写摘要：成功结果才落库，失败项记可读原因，未跑项不写半截结果
            RunSummary summary = new RunSummary("v1", quarters);
            int success = 0, failed = 0, notRun = 0;
            for (Job j : all) {
                switch (j.getStatus()) {
                    case SUCCESS -> {
                        success++;
                        if (j.getResult() != null) summary.addResult(j.getResult());
                        summary.addLandedQuarter(j.getQuarter());
                    }
                    case FAILED -> {
                        failed++;
                        summary.addFailedStep(j.getIndicatorCode(), j.getQuarter(),
                                "复算作业", j.getReason());
                    }
                    case NOT_RUN -> notRun++;
                    default -> { /* PENDING 不应出现 */ }
                }
            }
            return new QueueResult(all, total, completed.get(), success, failed, notRun,
                    cancelled, summary);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 单个季度任务：内部指标串行，保证可复现。 */
    private List<Job> runQuarter(List<Job> jobs, String quarter,
                                List<MetricDefinition> defs, RecomputeTask task) {
        List<Job> out = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            if (cancelled) {
                job.setStatus(JobStatus.NOT_RUN);
                job.setReason("运行已取消，该项未跑、不写半截结果");
                out.add(job);
                continue; // 未跑不计入已完成进度
            }
            long t0 = System.nanoTime();
            try {
                RecomputeResult r = task.execute(defs.get(i), quarter);
                job.setElapsedMillis((System.nanoTime() - t0) / 1_000_000L);
                if (r != null && r.isFailed()) {
                    job.setStatus(JobStatus.FAILED);
                    job.setResult(r);
                    job.setReason(r.getFailureReason() == null ? "复算显式失败" : r.getFailureReason());
                } else {
                    job.setStatus(JobStatus.SUCCESS);
                    job.setResult(r);
                }
            } catch (Exception ex) {
                job.setElapsedMillis((System.nanoTime() - t0) / 1_000_000L);
                job.setStatus(JobStatus.FAILED);
                job.setResult(null); // 不写半截结果、不按 0 计入分母
                job.setReason("复算作业失败：" + describe(ex));
            }
            out.add(job);
            completed.incrementAndGet();
        }
        return out;
    }

    private static String describe(Throwable e) {
        if (e instanceof AppException a) return a.render();
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
