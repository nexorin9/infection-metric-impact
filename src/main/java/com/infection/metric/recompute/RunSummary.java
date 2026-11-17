package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AtomicFiles;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 《复算运行摘要》（任务37 / 任务8）：可复现性保证与运行摘要落盘。
 *
 * <p>复算输入 = 口径版本集合 + 数据源版本 + 期间，全部记入运行元数据；
 * 由这三项派生确定性 {@code run_id}（不含时间戳），同输入两次复算结果逐项相等。</p>
 *
 * <p>摘要按年度分区写入 {@code data/runs/<年度>/<运行标识>/summary.json}，
 * 同时把每个复算结果原子写进同目录的 {@code results/}；摘要里的
 * {@code results_dir} 与 {@code artifacts} 可定位该次运行的全部产物。</p>
 */
public final class RunSummary {

    /** 摘要中的失败步骤条目。 */
    public static final class FailedStep {
        private final String indicatorCode;
        private final String quarter;
        private final String step;
        private final String reason;

        public FailedStep(String indicatorCode, String quarter, String step, String reason) {
            this.indicatorCode = indicatorCode == null ? "" : indicatorCode;
            this.quarter = quarter == null ? "" : quarter;
            this.step = step == null ? "" : step;
            this.reason = reason == null ? "" : reason;
        }

        public String getIndicatorCode() { return indicatorCode; }
        public String getQuarter() { return quarter; }
        public String getStep() { return step; }
        public String getReason() { return reason; }

        public ObjectNode toJson() {
            ObjectNode o = JsonSupport.object();
            o.put("indicator_code", indicatorCode);
            o.put("quarter", quarter);
            o.put("step", step);
            o.put("reason", reason);
            return o;
        }

        public static FailedStep fromJson(JsonNode n) {
            return new FailedStep(n.path("indicator_code").asText(""),
                    n.path("quarter").asText(""), n.path("step").asText(""),
                    n.path("reason").asText(""));
        }
    }

    private final String dataSourceVersion;
    private final LinkedHashSet<String> periods = new LinkedHashSet<>();
    private final LinkedHashSet<String> definitionVersions = new LinkedHashSet<>();
    private final List<RecomputeResult> results = new ArrayList<>();
    private final List<FailedStep> failedSteps = new ArrayList<>();
    private final List<String> unpublishedDefinitions = new ArrayList<>();
    private final LinkedHashSet<String> landedQuarters = new LinkedHashSet<>();
    private final List<String> artifacts = new ArrayList<>();
    private long fetchMillis;
    private long computeMillis;
    private String resultsDir = "";

    public RunSummary(String dataSourceVersion) {
        this(dataSourceVersion, List.of());
    }

    public RunSummary(String dataSourceVersion, Collection<String> declaredPeriods) {
        this.dataSourceVersion = dataSourceVersion == null ? "v1" : dataSourceVersion;
        if (declaredPeriods != null) this.periods.addAll(declaredPeriods);
    }

    public void addPeriod(String quarter) {
        if (quarter != null && !quarter.isBlank()) periods.add(quarter);
    }

    public void addDefinitionVersion(String code, String version) {
        definitionVersions.add(code + "|" + version);
    }

    public void addResult(RecomputeResult r) {
        results.add(r);
        addPeriod(r.getQuarter());
        addDefinitionVersion(r.getIndicatorCode(), r.getDefinitionVersion());
    }

    public void addFailedStep(String code, String quarter, String step, String reason) {
        failedSteps.add(new FailedStep(code, quarter, step, reason));
    }

    public void addUnpublished(String code, String version) {
        unpublishedDefinitions.add(code + "|" + version);
    }

    public void addLandedQuarter(String quarter) {
        if (quarter != null && !quarter.isBlank()) landedQuarters.add(quarter);
    }

    public void addFetchMillis(long millis) { this.fetchMillis += millis; }
    public void addComputeMillis(long millis) { this.computeMillis += millis; }
    public void setTimings(long fetch, long compute) {
        this.fetchMillis = fetch;
        this.computeMillis = compute;
    }

    public void setResultsDir(String dir) {
        this.resultsDir = dir == null ? "" : dir;
    }

    public void addArtifact(String relativePath) {
        if (relativePath != null && !relativePath.isBlank()) artifacts.add(relativePath);
    }

    // ---------------------------------------------------------------- 可复现性

    /** 输入指纹：排序后的口径版本集合 + 数据源版本 + 期间。同输入必相同。 */
    public String reproducibilityKey() {
        List<String> dv = new ArrayList<>(definitionVersions);
        Collections.sort(dv);
        List<String> ps = new ArrayList<>(periods);
        Collections.sort(ps);
        return Hashes.sha256Of(String.join(";", dv), dataSourceVersion, String.join(";", ps));
    }

    /** 确定性运行标识（不含时间戳，同输入相同）。 */
    public String runId() {
        return "run-" + Hashes.sha256(reproducibilityKey()).substring(0, 16);
    }

    // ---------------------------------------------------------------- 摘要内容

    public String getDataSourceVersion() { return dataSourceVersion; }
    public List<String> getPeriods() { return List.copyOf(periods); }
    public List<String> getDefinitionVersions() { return List.copyOf(definitionVersions); }
    public List<RecomputeResult> getResults() { return List.copyOf(results); }
    public List<FailedStep> getFailedSteps() { return List.copyOf(failedSteps); }
    public List<String> getUnpublishedDefinitions() { return List.copyOf(unpublishedDefinitions); }
    public List<String> getLandedQuarters() { return List.copyOf(landedQuarters); }
    public List<String> getArtifacts() { return List.copyOf(artifacts); }
    public String getResultsDir() { return resultsDir; }
    public long getFetchMillis() { return fetchMillis; }
    public long getComputeMillis() { return computeMillis; }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("run_id", runId());
        ObjectNode repro = o.putObject("reproducibility");
        ArrayNode dv = repro.putArray("definition_versions");
        sorted(definitionVersions).forEach(dv::add);
        repro.put("data_source_version", dataSourceVersion);
        ArrayNode ps = repro.putArray("periods");
        sorted(periods).forEach(ps::add);
        repro.put("input_fingerprint", reproducibilityKey());

        ArrayNode rs = o.putArray("results");
        for (RecomputeResult r : results) rs.add(r.toJson());

        ArrayNode metrics = o.putArray("metrics");
        for (RecomputeResult r : results) {
            ObjectNode m = metrics.addObject();
            m.put("indicator_code", r.getIndicatorCode());
            m.put("definition_version", r.getDefinitionVersion());
            m.put("quarter", r.getQuarter());
            if (r.getNumerator() == null) m.putNull("numerator"); else m.put("numerator", r.getNumerator());
            if (r.getDenominator() == null) m.putNull("denominator"); else m.put("denominator", r.getDenominator());
            m.put("status", r.getStatus().name());
        }

        ArrayNode fails = o.putArray("failed_steps");
        for (FailedStep f : failedSteps) fails.add(f.toJson());
        ArrayNode unpub = o.putArray("unpublished_definitions");
        for (String u : unpublishedDefinitions) unpub.add(u);
        ArrayNode landed = o.putArray("landed_quarters");
        sorted(landedQuarters).forEach(landed::add);

        o.put("fetch_millis", fetchMillis);
        o.put("compute_millis", computeMillis);
        o.put("results_dir", resultsDir);
        ArrayNode arts = o.putArray("artifacts");
        for (String a : artifacts) arts.add(a);
        return o;
    }

    private static List<String> sorted(Collection<String> c) {
        List<String> out = new ArrayList<>(c);
        Collections.sort(out);
        return out;
    }

    /** 从落盘 JSON 读回摘要，用于核对字段齐全与定位产物。 */
    public static RunSummary fromJson(JsonNode n) {
        JsonNode repro = n.path("reproducibility");
        RunSummary s = new RunSummary(repro.path("data_source_version").asText("v1"));
        for (JsonNode p : repro.path("periods")) s.addPeriod(p.asText(""));
        JsonNode rs = n.path("results");
        if (rs.isArray()) for (JsonNode r : rs) s.addResult(RecomputeResult.fromJson(r));
        JsonNode fs = n.path("failed_steps");
        if (fs.isArray()) for (JsonNode f : fs) {
            FailedStep step = FailedStep.fromJson(f);
            s.addFailedStep(step.getIndicatorCode(), step.getQuarter(), step.getStep(), step.getReason());
        }
        JsonNode us = n.path("unpublished_definitions");
        if (us.isArray()) for (JsonNode u : us) {
            String t = u.asText("");
            int i = t.indexOf('|');
            s.addUnpublished(i >= 0 ? t.substring(0, i) : t, i >= 0 ? t.substring(i + 1) : "");
        }
        JsonNode ls = n.path("landed_quarters");
        if (ls.isArray()) for (JsonNode l : ls) s.addLandedQuarter(l.asText(""));
        s.setTimings(n.path("fetch_millis").asLong(), n.path("compute_millis").asLong());
        s.setResultsDir(n.path("results_dir").asText(""));
        JsonNode as = n.path("artifacts");
        if (as.isArray()) for (JsonNode a : as) s.addArtifact(a.asText(""));
        return s;
    }

    // ---------------------------------------------------------------- 落盘

    /**
     * 按年度分区落盘：{@code <dataRoot>/runs/<年度>/<runId>/summary.json}
     * 并把每个结果原子写入 {@code results/}；返回运行目录。
     *
     * <p>摘要内只记录逻辑相对路径，不含临时目录绝对路径，保证同输入摘要内容可复算。</p>
     */
    public Path write(Path dataRoot, int year) {
        String y = String.valueOf(year);
        String logical = "data/runs/" + y + "/" + runId();
        setResultsDir(logical);
        Path runDir = dataRoot.resolve("runs").resolve(y).resolve(runId());
        Path resultsPath = runDir.resolve("results");
        AtomicFiles.ensureDir(resultsPath);

        artifacts.clear();
        for (RecomputeResult r : results) {
            String file = safeName(r.getIndicatorCode()) + "__" + safeName(r.getQuarter()) + ".json";
            JsonSupport.writeAtomic(resultsPath.resolve(file), r.toJson());
            artifacts.add("results/" + file);
        }
        JsonSupport.writeAtomic(runDir.resolve("summary.json"), toJson());
        return runDir;
    }

    /** 由摘要里的 artifacts 相对路径定位该次运行的全部产物。 */
    public List<Path> artifactPaths(Path runDir) {
        List<Path> out = new ArrayList<>();
        for (String a : artifacts) out.add(runDir.resolve(a));
        return out;
    }

    private static String safeName(String raw) {
        return raw == null ? "unknown" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
