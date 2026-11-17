package com.infection.metric.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AtomicFiles;
import com.infection.metric.metriclib.BenchmarkSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 主干第一条端到端产物：把官方基准集 27 项口径建成依赖图，改一条口径后，
 * 产出第一份《口径变更影响清单》JSON 到 {@code out/impact/}，含受影响指标数组。
 *
 * <p>流程：基准集指标 → {@link FingerprintEdge}（分母内容指纹）→ {@link ImpactAnalyzer}
 * 建图并分析 → {@link ImpactList} → 原子落盘。落盘只留聚合值与口径元数据，不写患者明细。
 */
public final class ImpactListWriter {

    public static final String BENCHMARK_VERSION = "2026.1";
    public static final String OFFICIAL_SOURCE =
            "《病案管理质量控制指标（2021年版）》（国卫办医函〔2021〕28号附件）";

    private ImpactListWriter() {}

    /** 用 27 项官方基准口径构造一个纯指标节点图（链状复用边），供主干与对照验收。 */
    public static MetricDependencyGraph buildBenchmarkGraph(BenchmarkSet bs) {
        MetricDependencyGraph g = new MetricDependencyGraph();
        for (String code : bs.codes()) g.addNode(code + "|" + BENCHMARK_VERSION);
        // 四个复用分母：按编码排序后串成链（后一个复用前一个的分母），无环、无悬空。
        for (String name : bs.declaredGroupCounts().keySet()) {
            List<String> group = new ArrayList<>(bs.denominatorGroupCodes(name));
            group.sort(String::compareTo);
            for (int i = 1; i < group.size(); i++) {
                String from = group.get(i) + "|" + BENCHMARK_VERSION;
                String to = group.get(i - 1) + "|" + BENCHMARK_VERSION;
                g.addEdge(from, to); // from 依赖 to
            }
        }
        return g.build();
    }

    /** 由基准集 + 变更指标 + 已落库季度，构造《口径变更影响清单》数据对象。 */
    public static ImpactList buildFromBenchmark(BenchmarkSet bs, String changedCode,
                                                Collection<String> storedPeriods) {
        List<FingerprintEdge> fe = new ArrayList<>();
        for (String code : bs.codes()) {
            JsonNode n = bs.byCode(code);
            if (n != null) fe.add(FingerprintEdge.fromBenchmark(n, BENCHMARK_VERSION));
        }
        String changedId = changedCode + "|" + BENCHMARK_VERSION;
        FingerprintEdge changed = fe.stream()
                .filter(e -> e.getId().equals(changedId)).findFirst().orElse(null);
        String changedTitle = changed != null ? changed.getTitle() : changedCode;

        ImpactAnalyzer analyzer = new ImpactAnalyzer()
                .addEntries(fe)
                .withStoredPeriods(storedPeriods)
                .build();
        ImpactAnalysisResult result = analyzer.analyze(changedId, 1);

        ImpactList.ChangeEntry change = new ImpactList.ChangeEntry(
                changedCode, BENCHMARK_VERSION, changedTitle,
                "口径 " + changedCode + " 发生变更，复用同一分母的指标需一并复核并重算相关历史季度");

        List<ImpactList.AffectedIndicator> affected = new ArrayList<>();
        for (ImpactAnalysisResult.ImpactRow row : result.getAffected()) {
            String source = "复用「" + (changed != null ? changed.getDenominatorLabel() : "同分母")
                    + "」分母；" + row.getEvidence();
            affected.add(new ImpactList.AffectedIndicator(row.getCode(), row.getName(),
                    row.getVersion(), row.getDepth(),
                    "复用同一分母口径，需随变更一并复核", source));
        }

        String note = result.getNote();
        if (affected.isEmpty()) {
            note = "该口径无下游依赖，无需重算；" + result.recomputeQuartersText();
        } else {
            note = note + "；" + result.recomputeQuartersText();
        }
        return new ImpactList(change, result.getRecomputeQuarters(), affected, OFFICIAL_SOURCE, note);
    }

    /**
     * 产出第一份清单到 {@code outRoot/impact/impact-list-<变更编码>.json}。
     *
     * @param outRoot 输出根目录（测试用 @TempDir），产物落在其 impact/ 子目录
     * @return 实际写出的 JSON 路径
     */
    public static Path writeFirstImpactList(Path outRoot, BenchmarkSet bs, String changedCode,
                                            Collection<String> storedPeriods) {
        Path dir = outRoot.resolve("impact");
        AtomicFiles.ensureDir(dir);
        Path file = dir.resolve("impact-list-" + sanitize(changedCode) + ".json");
        buildFromBenchmark(bs, changedCode, storedPeriods).writeTo(file);
        return file;
    }

    private static String sanitize(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
