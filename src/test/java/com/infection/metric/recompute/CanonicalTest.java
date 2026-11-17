package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务33：口径归一化与内容指纹 —— 交换律等价指纹相同、仅差一词的两个官方分母指纹不同。 */
class CanonicalTest {

    @Test
    void 交换律等价的口径指纹相同() {
        ConditionTree a = ConditionTree.and(
                ConditionTree.leaf("dept_code", "in", List.of("0301", "0302")),
                ConditionTree.leaf("wound_class", "eq", "III"));
        ConditionTree b = ConditionTree.and(
                ConditionTree.leaf("wound_class", "eq", "III"),
                ConditionTree.leaf("dept_code", "in", List.of("0302", "0301")));

        assertEquals(CanonicalFingerprint.ofTree(a), CanonicalFingerprint.ofTree(b),
                "AND 子节点交换 + in 元素顺序无关，指纹应相同");
        assertTrue(CanonicalFingerprint.equivalent(a, b));
        assertEquals(CanonicalFingerprint.canonicalJson(a), CanonicalFingerprint.canonicalJson(b));

        ConditionTree or1 = ConditionTree.or(
                ConditionTree.leaf("dept_code", "eq", "0301"),
                ConditionTree.leaf("wound_class", "eq", "III"));
        ConditionTree or2 = ConditionTree.or(
                ConditionTree.leaf("wound_class", "eq", "III"),
                ConditionTree.leaf("dept_code", "eq", "0301"));
        assertEquals(CanonicalFingerprint.ofTree(or1), CanonicalFingerprint.ofTree(or2),
                "OR 交换律同样等价");
    }

    @Test
    void in集合去重并排序后指纹相同() {
        ConditionTree withDup = ConditionTree.leaf("dept_code", "in",
                List.of("0302", "0301", "0302"));
        ConditionTree canonical = ConditionTree.leaf("dept_code", "in",
                List.of("0301", "0302"));
        assertEquals(CanonicalFingerprint.ofTree(canonical), CanonicalFingerprint.ofTree(withDup),
                "集合去重并排序，重复元素不影响指纹");
    }

    @Test
    void 仅差一词的两个官方分母指纹不同() {
        JsonNode groups = JsonSupport.readResource("/benchmarks/mrq_control_2021.json")
                .path("denominator_groups");
        assertTrue(groups.has("同期住院手术患者病历总数"), "官方分母组应存在");
        assertTrue(groups.has("同期出院手术患者病历总数"), "官方分母组应存在");
        assertEquals(2, groups.path("同期住院手术患者病历总数").path("expected_count").asInt());
        assertEquals(2, groups.path("同期出院手术患者病历总数").path("expected_count").asInt());

        ConditionTree inHospital = ConditionTree.leaf("denominator_text", "eq", "同期住院手术患者病历总数");
        ConditionTree discharged = ConditionTree.leaf("denominator_text", "eq", "同期出院手术患者病历总数");

        assertNotEquals(CanonicalFingerprint.ofTree(inHospital), CanonicalFingerprint.ofTree(discharged),
                "仅差「住院/出院」一词，必须是两个不同指纹，依赖边不能按名称误连");
        assertFalse(CanonicalFingerprint.equivalent(inHospital, discharged));

        MetricDefinition d1 = MetricDefinition.builder("M-A", "v1")
                .denominator(inHospital).status(MetricStatus.PUBLISHED).build();
        MetricDefinition d2 = MetricDefinition.builder("M-B", "v1")
                .denominator(discharged).status(MetricStatus.PUBLISHED).build();
        assertNotEquals(CanonicalFingerprint.dependencyEdgeKey(d1),
                CanonicalFingerprint.dependencyEdgeKey(d2), "两个官方分母的依赖边键不同");
    }

    @Test
    void 指纹同时用于缓存键与依赖边键且同内容稳定() {
        MetricDefinition def = MetricDefinition.builder("MER-TQ-01", "2026.1")
                .numerator(ConditionTree.and(
                        ConditionTree.leaf("quarter", "eq", "2026Q1"),
                        ConditionTree.leaf("wound_class", "eq", "III")))
                .denominator(ConditionTree.leaf("quarter", "eq", "2026Q1"))
                .status(MetricStatus.PUBLISHED)
                .build();

        assertEquals(def.contentFingerprint(), CanonicalFingerprint.cacheKey(def),
                "复算缓存键 = 口径规范化内容指纹");
        assertEquals(def.denominatorFingerprint(), CanonicalFingerprint.dependencyEdgeKey(def),
                "依赖边键 = 分母定义内容哈希");
        MetricDefinition same = def.toBuilder().build();
        assertEquals(CanonicalFingerprint.cacheKey(def), CanonicalFingerprint.cacheKey(same),
                "同内容重复构建，缓存键稳定");
        assertTrue(CanonicalFingerprint.equivalent(def, same));
    }
}
