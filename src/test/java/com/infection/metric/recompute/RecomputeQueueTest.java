package com.infection.metric.recompute;

import com.infection.metric.common.AppException;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 任务36：失败隔离、并发与串行一致、取消与进度、作业独立状态。 */
class RecomputeQueueTest {

    private static List<MetricDefinition> defs() {
        return List.of(
                MetricDefinition.builder("M1", "v1").status(MetricStatus.PUBLISHED).build(),
                MetricDefinition.builder("M2", "v1").status(MetricStatus.PUBLISHED).build(),
                MetricDefinition.builder("M3", "v1").status(MetricStatus.PUBLISHED).build());
    }

    private static final List<String> QUARTERS = List.of("2026Q1", "2026Q2");

    private static RecomputeQueue.RecomputeTask successTask() {
        return (d, q) -> RecomputeResult.success(
                d.getIndicatorCode(), d.getDefinitionVersion(), q, 10, 20, "ck", 1, 1);
    }

    @Test
    void 注入一个失败作业后其它项仍完成且失败进摘要() {
        RecomputeQueue.RecomputeTask withFailure = (d, q) -> {
            if (d.getIndicatorCode().equals("M2") && q.equals("2026Q2")) {
                throw new AppException("E_RECOMPUTE_001", "指标 M2 取数失败");
            }
            return RecomputeResult.success(
                    d.getIndicatorCode(), d.getDefinitionVersion(), q, 10, 20, "ck", 1, 1);
        };

        RecomputeQueue.QueueResult res = new RecomputeQueue(4).run(defs(), QUARTERS, withFailure);
        assertEquals(6, res.getTotal(), "3 指标 × 2 季度");
        assertEquals(6, res.getCompleted());
        assertEquals(5, res.getSuccessCount(), "单项失败不中断其它项");
        assertEquals(1, res.getFailedCount());
        assertEquals(0, res.getNotRunCount());

        RecomputeQueue.Job failed = res.getJobs().stream()
                .filter(j -> j.getStatus() == RecomputeQueue.JobStatus.FAILED)
                .findFirst().orElseThrow();
        assertEquals("M2", failed.getIndicatorCode());
        assertNull(failed.getResult(), "失败不写半截结果、不按 0 计入分母");

        assertEquals(1, res.getSummary().getFailedSteps().size());
        assertEquals("M2", res.getSummary().getFailedSteps().get(0).getIndicatorCode());
        assertEquals("2026Q2", res.getSummary().getFailedSteps().get(0).getQuarter());
        assertTrue(res.getSummary().getFailedSteps().get(0).getReason().contains("M2"),
                "失败原因可读");
        assertEquals(5, res.getSummary().getResults().size(), "成功结果保留在摘要");
    }

    @Test
    void 并发与串行结果逐项一致() {
        RecomputeQueue.QueueResult serial = new RecomputeQueue(1).run(defs(), QUARTERS, successTask());
        RecomputeQueue.QueueResult parallel = new RecomputeQueue(4).run(defs(), QUARTERS, successTask());

        assertEquals(serial.getJobs().size(), parallel.getJobs().size());
        assertEquals(6, parallel.getCompleted());
        for (int i = 0; i < serial.getJobs().size(); i++) {
            RecomputeQueue.Job a = serial.getJobs().get(i);
            RecomputeQueue.Job b = parallel.getJobs().get(i);
            assertEquals(a.getIndicatorCode(), b.getIndicatorCode());
            assertEquals(a.getQuarter(), b.getQuarter());
            assertEquals(a.getStatus(), b.getStatus());
            assertEquals(a.getResult().getNumerator(), b.getResult().getNumerator());
            assertEquals(a.getResult().getDenominator(), b.getResult().getDenominator());
        }
    }

    @Test
    void 取消后已落盘结果保留未完成标未跑不写半截结果() {
        RecomputeQueue[] holder = new RecomputeQueue[1];
        AtomicInteger executed = new AtomicInteger();
        RecomputeQueue.RecomputeTask cancelTask = (d, q) -> {
            if (executed.incrementAndGet() == 1) {
                holder[0].cancel(); // 第一个作业执行时请求取消
            }
            return RecomputeResult.success(
                    d.getIndicatorCode(), d.getDefinitionVersion(), q, 10, 20, "ck", 1, 1);
        };
        RecomputeQueue queue = new RecomputeQueue(1); // 单线程保证取消行为确定
        holder[0] = queue;

        RecomputeQueue.QueueResult res = queue.run(defs(), QUARTERS, cancelTask);
        assertTrue(res.isCancelled());
        assertEquals(1, res.getSuccessCount(), "取消前已完成的作业结果保留");
        assertEquals(5, res.getNotRunCount(), "其余作业标为未跑");
        assertTrue(res.getCompleted() < res.getTotal(), "进度显示未全部完成");
        assertTrue(res.progressText().startsWith("1/"), "进度 = 已完成 / 总数");

        assertEquals(1, res.getSummary().getResults().size(), "已落盘结果保留在摘要");
        for (RecomputeQueue.Job j : res.getJobs()) {
            if (j.getStatus() == RecomputeQueue.JobStatus.NOT_RUN) {
                assertNull(j.getResult(), "未跑项不写半截结果");
                assertFalse(j.getReason().isBlank(), "未跑项带可读原因");
            }
            assertTrue(j.getElapsedMillis() >= 0, "每项作业独立耗时");
        }
    }

    @Test
    void 每项作业独立状态与耗时可序列化() {
        RecomputeQueue.QueueResult res = new RecomputeQueue(2).run(defs(), QUARTERS, successTask());
        assertEquals(6, res.getJobs().size());
        for (RecomputeQueue.Job j : res.getJobs()) {
            assertNotNull(j.getStatus());
            assertEquals(RecomputeQueue.JobStatus.SUCCESS, j.getStatus());
            assertTrue(j.getElapsedMillis() >= 0);
            assertFalse(j.toJson().path("indicator_code").asText().isBlank());
        }
        assertEquals(6, res.toJson().path("total").asInt());
    }
}
