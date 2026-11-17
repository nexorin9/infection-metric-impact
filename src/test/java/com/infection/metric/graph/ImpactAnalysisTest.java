package com.infection.metric.graph;

import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.Hashes;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.BenchmarkSet;
import com.infection.metric.metriclib.MetricDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 任务4：指纹（分母内容哈希）匹配 + 影响面清单 + 官方基准对照断言。 */
class ImpactAnalysisTest {

    @Test
    void 同名称不同定义不误连() {
        // 两个口径名称（分母标签）相同，但分母定义内容不同 → 内容指纹必须不同。
        MetricDefinition m1 = MetricDefinition.builder("M1", "v1").title("甲")
                .denominator(ConditionTree.leaf("quarter", "eq", "2026Q1"))
                .periods(List.of("2026Q1")).build();
        MetricDefinition m2 = MetricDefinition.builder("M2", "v1").title("乙")
                .denominator(ConditionTree.leaf("quarter", "eq", "2026Q2"))
                .periods(List.of("2026Q1")).build();

        FingerprintEdge e1 = FingerprintEdge.fromMetric(m1, "同期出院患者病历总数");
        FingerprintEdge e2 = FingerprintEdge.fromMetric(m2, "同期出院患者病历总数");
        assertEquals(e1.getDenominatorLabel(), e2.getDenominatorLabel(), "名称相同");
        assertNotEquals(e1.getFingerprint(), e2.getFingerprint(), "定义不同 → 指纹不同");
        assertFalse(e1.matches(e2));

        ImpactAnalyzer a = new ImpactAnalyzer().addEntry(e1).addEntry(e2).build();
        assertTrue(a.isBuilt());
        assertEquals(2, a.groups().size(), "按指纹应分成两组，不按名称合并");
        assertEquals(0, a.reusedGroupCount());
        assertEquals(1, a.groupSize(e1.getFingerprint()));
        assertEquals(1, a.groupSize(e2.getFingerprint()));

        ImpactAnalysisResult r = a.analyze(e1.getId(), 1);
        assertTrue(r.isEmpty(), "名称相同但定义不同时不得误连为影响关系");
    }

    @Test
    void 两个仅差一词的官方分母是两个不同口径不建错边() {
        BenchmarkSet bs = BenchmarkSet.load();
        FingerprintEdge inSurg = FingerprintEdge.fromBenchmark(bs.byCode("MER-TL-02"), "2026.1");
        FingerprintEdge outSurg = FingerprintEdge.fromBenchmark(bs.byCode("MER-TQ-05"), "2026.1");

        assertTrue(inSurg.getDenominatorLabel().contains("住院"));
        assertTrue(outSurg.getDenominatorLabel().contains("出院"));
        assertNotEquals(inSurg.getDenominatorLabel(), outSurg.getDenominatorLabel());
        assertNotEquals(inSurg.getFingerprint(), outSurg.getFingerprint(), "仅差一词仍是两个口径");
        assertFalse(inSurg.matches(outSurg));

        ImpactAnalyzer a = new ImpactAnalyzer().addEntry(inSurg).addEntry(outSurg).build();
        assertEquals(2, a.groups().size());
        assertEquals(0, a.reusedGroupCount());
        assertTrue(a.analyze(inSurg.getId(), 2).isEmpty(), "不得在两个口径间建错边");
    }

    @Test
    void 对照基准集_8项复用与四分母归属数一致() {
        BenchmarkSet bs = BenchmarkSet.load();
        assertEquals(27, bs.size());
        List<FingerprintEdge> fe = new ArrayList<>();
        for (String code : bs.codes()) {
            fe.add(FingerprintEdge.fromBenchmark(bs.byCode(code), "2026.1"));
        }
        ImpactAnalyzer a = new ImpactAnalyzer().addEntries(fe).build();
        assertTrue(a.isBuilt());
        assertEquals(27, a.entries().size());

        // 用「分母定义内容」的 sha256 作指纹（不按名称），四个分母归属数与官方一致。
        Map<String, Integer> declared = bs.declaredGroupCounts();
        assertEquals(4, declared.size());
        for (Map.Entry<String, Integer> e : declared.entrySet()) {
            String fp = Hashes.sha256(e.getKey()); // 这四个分母的 denominator_text 与分母名一致
            assertEquals(e.getValue().intValue(), a.groupSize(fp),
                    "分母「" + e.getKey() + "」按内容指纹分组应与官方一致");
            assertEquals(bs.denominatorGroup(e.getKey()), a.groupSize(fp));
        }

        // 8 项共用「同期出院患者病历总数」的结构完整复现
        String fp8 = Hashes.sha256("同期出院患者病历总数");
        assertEquals(8, a.groupSize(fp8));
        Set<String> group8 = a.groups().get(fp8).stream()
                .map(FingerprintEdge::getCode).collect(Collectors.toSet());
        assertEquals(Set.of("MER-TL-03", "MER-TL-04", "MER-TQ-01", "MER-TQ-02",
                "MER-TQ-03", "MER-TQ-04", "MER-TQ-07", "MER-TQ-09"), group8);

        // 仅差一词的两个手术分母互不连边：改「住院手术」不影响「出院手术」组
        ImpactAnalysisResult r = a.analyze("MER-TL-02|2026.1", 2);
        Set<String> codes = new HashSet<>(r.affectedCodes());
        assertTrue(codes.contains("MER-D&T-04"), "同组（住院手术）指标应受影响");
        assertFalse(codes.contains("MER-TQ-05"));
        assertFalse(codes.contains("MER-TQ-06"));
    }

    @Test
    void 影响面含受影响指标与需重算季度_取交集() {
        List<FingerprintEdge> fe = List.of(
                new FingerprintEdge("A", "v1", "甲", "同期出院患者病历总数", "FP_SAME",
                        List.of("2026Q1", "2026Q2", "2026Q3")),
                new FingerprintEdge("B", "v1", "乙", "同期出院患者病历总数", "FP_SAME",
                        List.of("2026Q1", "2026Q2", "2026Q3")),
                new FingerprintEdge("C", "v1", "丙", "同期出院患者病历总数", "FP_SAME",
                        List.of("2026Q1", "2026Q2", "2026Q3")));

        ImpactAnalyzer a = new ImpactAnalyzer().addEntries(fe)
                .withStoredPeriods(List.of("2026Q2", "2026Q4")).build();
        ImpactAnalysisResult r = a.analyze("A|v1", 1);

        assertEquals(2, r.getAffected().size());
        assertEquals(Set.of("B", "C"), new HashSet<>(r.affectedCodes()));
        assertEquals(List.of("2026Q2"), r.getRecomputeQuarters(), "scope.periods 与已落库分区求交");
        assertEquals(Set.of(1), r.depths(), "默认非递归，只出现直接层");
        assertFalse(r.hasErrors());
        assertFalse(r.getAffected().get(0).getEvidence().isBlank(), "每条结果带来源边依据");
        assertFalse(r.getAffected().get(0).getExplanation().isBlank(), "每条结果带人话说明");

        // 无交集时明确写「无历史季度需重算」
        ImpactAnalyzer a2 = new ImpactAnalyzer().addEntries(fe)
                .withStoredPeriods(List.of("2025Q4")).build();
        ImpactAnalysisResult r2 = a2.analyze("A|v1", 1);
        assertTrue(r2.getRecomputeQuarters().isEmpty());
        assertEquals("无历史季度需重算", r2.recomputeQuartersText());
    }

    @Test
    void 默认非递归_跨层显式depth标注_默认不含跨层项() {
        List<FingerprintEdge> fe = List.of(
                new FingerprintEdge("A", "v1", "甲", "分母X", "FPX", List.of()),
                new FingerprintEdge("B", "v1", "乙", "分母X", "FPX", List.of()),
                new FingerprintEdge("C", "v1", "丙", "分母X", "FPX", List.of()));
        // 额外节点 Z 依赖 A，制造第二层；A/B/C 同指纹连到分母节点。
        ImpactAnalyzer a = new ImpactAnalyzer().addEntries(fe)
                .addNode("Z|v1")
                .addEdge(new DependencyEdge("Z|v1", "A|v1", "FPX", "Z 复用 A 的口径"))
                .build();
        assertTrue(a.isBuilt());

        ImpactAnalysisResult def = a.analyze("A|v1"); // 默认 depth=1
        assertEquals(Set.of(1), def.depths(), "默认结果不出现跨层项");
        assertFalse(def.affectedCodes().contains("Z"));

        ImpactAnalysisResult deep = a.analyze("A|v1", 2); // 显式跨层
        assertTrue(deep.depths().contains(2), "depth=2 出现第二层");
        assertTrue(deep.getAffected().stream()
                .anyMatch(row -> row.getNodeId().equals("Z|v1") && row.getDepth() == 2));
    }

    @Test
    void 成环时影响面为空且错误文案可读() {
        FingerprintEdge eA = new FingerprintEdge("A", "v1", "甲", "分母X", "FPX", List.of("2026Q1"));
        ImpactAnalyzer a = new ImpactAnalyzer().addEntry(eA)
                .addNode("X|v1").addNode("Y|v1").addNode("Z|v1")
                .addEdge(new DependencyEdge("X|v1", "Y|v1", "", "X 依赖 Y"))
                .addEdge(new DependencyEdge("Y|v1", "Z|v1", "", "Y 依赖 Z"))
                .addEdge(new DependencyEdge("Z|v1", "X|v1", "", "Z 依赖 X"))
                .build();
        assertTrue(a.isInvalid());
        assertFalse(a.isBuilt());

        ImpactAnalysisResult r = a.analyze("A|v1", 1);
        assertTrue(r.isEmpty(), "成环时影响面为空");
        assertTrue(r.hasErrors());
        String text = r.renderErrors();
        assertTrue(text.contains("成环"));
        assertTrue(text.contains(ErrorCodes.E_GRAPH_001));
        assertTrue(text.contains("→"), "错误文案含成环指标链");
    }

    @Test
    void 悬空时影响面为空且错误文案可读() {
        FingerprintEdge eA = new FingerprintEdge("A", "v1", "甲", "分母X", "FPX", List.of("2026Q1"));
        ImpactAnalyzer a = new ImpactAnalyzer().addEntry(eA)
                .addEdge(new DependencyEdge("A|v1", "MISSING|v1", "", "A 依赖不存在的口径"))
                .build();
        assertTrue(a.isInvalid());

        ImpactAnalysisResult r = a.analyze("A|v1", 1);
        assertTrue(r.isEmpty());
        assertTrue(r.hasErrors());
        String text = r.renderErrors();
        assertTrue(text.contains("悬空"));
        assertTrue(text.contains(ErrorCodes.E_GRAPH_002));
        assertTrue(text.contains("MISSING"));
    }
}
