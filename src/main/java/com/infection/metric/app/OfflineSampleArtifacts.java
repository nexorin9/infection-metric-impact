package com.infection.metric.app;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.graph.FingerprintEdge;
import com.infection.metric.graph.ImpactAnalysisResult;
import com.infection.metric.graph.ImpactAnalyzer;
import com.infection.metric.graph.ImpactListWriter;
import com.infection.metric.metriclib.BenchmarkSet;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;
import com.infection.metric.recompute.DiffReportRenderer;
import com.infection.metric.recompute.QuarterRecords;
import com.infection.metric.recompute.RecomputeComparison;
import com.infection.metric.recompute.RecomputeEngine;
import com.infection.metric.recompute.RunSummary;
import com.infection.metric.recompute.SyntheticDataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线演示的真实产物工厂：把 M2 / M4 的算法真正跑一遍并落盘，供 CLI 回显与桌面窗口共同读取。
 *
 * <p>不做任何手写演示数字：口径来自规则化合成生成器（39 条）、记录来自 1200 条出院记录 +
 * 180 条院感明细，影响面由分母指纹依赖图实时展开，差异说明由新旧两版口径真实双版本对拍得出。
 * 三份产物均落在 {@code out/offline-sample/}，界面与终端回显只读取这些已落盘结果。</p>
 */
public final class OfflineSampleArtifacts {

    public static final Path OUT = Paths.get("out", "offline-sample");
    public static final Path RECOMPUTE = OUT.resolve("recompute-summary.json");
    public static final Path DIFF = OUT.resolve("diff-report.json");
    public static final Path IMPACT = OUT.resolve("impact-list.json");
    public static final String CHANGED_CODE = "MER-TQ-01";
    public static final String NEW_VERSION = "2026.2";

    private OfflineSampleArtifacts() {}

    /** 一次真实运行的全部结果快照。 */
    public static final class Snapshot {
        public final SyntheticDataset dataset;
        public final RunSummary summary;
        public final ImpactAnalysisResult impact;
        public final RecomputeComparison comparison;
        public final List<DiffReportRenderer.Segment> segments;
        public final String resultId;
        public final String quarter;
        public final Path impactListPath;

        Snapshot(SyntheticDataset dataset, RunSummary summary, ImpactAnalysisResult impact,
                 RecomputeComparison comparison, List<DiffReportRenderer.Segment> segments,
                 String resultId, String quarter, Path impactListPath) {
            this.dataset = dataset;
            this.summary = summary;
            this.impact = impact;
            this.comparison = comparison;
            this.segments = segments;
            this.resultId = resultId;
            this.quarter = quarter;
            this.impactListPath = impactListPath;
        }
    }

    /** 真跑复算、影响面展开与双版本对拍，并把三份产物原子落盘。 */
    public static Snapshot computeAndPersist(Path dataRoot) throws IOException {
        SyntheticDataset ds = SyntheticDataset.load();
        int year = Year.now().getValue();
        RunSummary summary = ds.recomputeAll(dataRoot, year);
        Files.createDirectories(OUT);
        JsonSupport.writeAtomic(RECOMPUTE, summary.toJson());
        String resultId = summary.runId();

        // 影响面：按官方 27 项基准的分母定义内容指纹建图（8/2/2/2 复用结构）
        BenchmarkSet bs = BenchmarkSet.load();
        List<FingerprintEdge> edges = new ArrayList<>();
        for (String code : bs.codes()) {
            edges.add(FingerprintEdge.fromBenchmark(bs.byCode(code),
                    ImpactListWriter.BENCHMARK_VERSION, ds.quarters()));
        }
        String changedId = CHANGED_CODE + "|" + ImpactListWriter.BENCHMARK_VERSION;
        ImpactAnalyzer analyzer = new ImpactAnalyzer()
                .addEntries(edges)
                .withStoredPeriods(ds.quarters())
                .build();
        ImpactAnalysisResult impact = analyzer.analyze(changedId, 1);
        JsonSupport.writeAtomic(IMPACT, impact.toJson());
        Path impactListPath = ImpactListWriter.writeFirstImpactList(
                Paths.get("out"), bs, CHANGED_CODE, ds.quarters());

        // 差异说明：同一指标新旧两版口径在 2026Q1 各复算一遍（新版把分子范围收窄到 0301 科室）
        SyntheticDataset.IndicatorSpec spec = ds.indicators().stream()
                .filter(s -> CHANGED_CODE.equals(s.getCode()))
                .findFirst()
                .orElseGet(() -> ds.indicators().get(0));
        MetricDefinition oldDef = spec.toDefinition();
        MetricDefinition newDef = MetricDefinition.builder(oldDef.getIndicatorCode(), NEW_VERSION)
                .title(oldDef.getTitle())
                .numerator(ConditionTree.and(oldDef.getNumerator(),
                        ConditionTree.leaf("dept_code", "in", List.of("0301"))))
                .denominator(oldDef.getDenominator())
                .exclusions(oldDef.getExclusions())
                .deptCodes(oldDef.getDeptCodes())
                .periods(oldDef.getPeriods())
                .dataSources(oldDef.getDataSources())
                .status(MetricStatus.PUBLISHED)
                .build();
        String quarter = ds.quarters().isEmpty() ? "2026Q1" : ds.quarters().get(0);
        RecomputeEngine engine = new RecomputeEngine(
                q -> QuarterRecords.of(ds.encounters(q), ds.encounters(q)),
                SyntheticDataset.DATA_SOURCE_VERSION);
        RecomputeComparison cmp = engine.compare(oldDef, newDef, quarter);
        DiffReportRenderer renderer = new DiffReportRenderer();
        List<DiffReportRenderer.Segment> segments = renderer.segments(cmp);
        ObjectNode diffJson = renderer.renderJson(cmp);
        diffJson.put("result_id", resultId);
        diffJson.put("quarter", quarter);
        diffJson.put("definition_version", NEW_VERSION);
        diffJson.put("persisted", true);
        JsonSupport.writeAtomic(DIFF, diffJson);

        return new Snapshot(ds, summary, impact, cmp, segments, resultId, quarter, impactListPath);
    }

    /** 从已落盘产物构造界面用的引用；没有产物时返回未落库引用，面板会按门禁拒绝显示。 */
    public static boolean diffReportPersisted() {
        return Files.isRegularFile(DIFF);
    }
}
