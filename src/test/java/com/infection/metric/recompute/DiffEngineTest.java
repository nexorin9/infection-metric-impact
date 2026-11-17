package com.infection.metric.recompute;

import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务7：两版口径结构化三桶差异 —— 归一化次序可证、三桶齐全、等价不产生差异、单条件只落一桶。 */
class DiffEngineTest {

    private static final ConditionTree DEN = ConditionTree.leaf("quarter", "eq", "2026Q1");

    private static MetricDefinition def(String version, ConditionTree numerator,
                                        List<ConditionTree> exclusions) {
        return MetricDefinition.builder("MER-TQ-01", version)
                .numerator(numerator)
                .denominator(DEN)
                .exclusions(exclusions)
                .deptCodes(List.of("0301", "0302"))
                .periods(List.of("2026Q1", "2026Q2"))
                .status(MetricStatus.PUBLISHED)
                .build();
    }

    @Test
    void 三桶齐全且条件级带字段名与旧值新值() {
        ConditionTree oldNum = ConditionTree.and(
                ConditionTree.leaf("quarter", "eq", "2026Q1"),
                ConditionTree.leaf("dept_code", "in", List.of("0301", "0302")),
                ConditionTree.leaf("grade_a_record", "eq", "true"));
        ConditionTree newNum = ConditionTree.and(
                ConditionTree.leaf("quarter", "eq", "2026Q1"),
                ConditionTree.leaf("dept_code", "in", List.of("0301", "0302")),
                ConditionTree.leaf("first_page_ontime", "eq", "true"));
        MetricDefinition oldDef = def("2026.0", oldNum,
                List.of(ConditionTree.leaf("wound_class", "eq", "I")));
        MetricDefinition newDef = def("2026.1", newNum,
                List.of(ConditionTree.leaf("wound_class", "eq", "I"),
                        ConditionTree.leaf("mdr_flag", "eq", "true")));

        DiffResult d = new DiffEngine().diff(oldDef, newDef);
        assertEquals(1, d.getNumeratorAdded().size(), "分子新增 1 条");
        assertEquals(1, d.getExclusionAdded().size(), "排除新增 1 条");
        assertEquals(1, d.getRemoved().size(), "移除 1 条");
        assertEquals(3, d.total());
        assertFalse(d.isEmpty());

        ConditionChange added = d.getNumeratorAdded().get(0);
        assertEquals("first_page_ontime", added.getField());
        assertNull(added.getOldValue());
        assertEquals("true", added.getNewValue());
        assertEquals(TreePosition.NUMERATOR, added.getPosition());

        ConditionChange removed = d.getRemoved().get(0);
        assertEquals("grade_a_record", removed.getField());
        assertEquals("true", removed.getOldValue());
        assertNull(removed.getNewValue());

        assertEquals("mdr_flag", d.getExclusionAdded().get(0).getField());
        assertEquals(TreePosition.EXCLUSION, d.getExclusionAdded().get(0).getPosition());
        assertFalse(added.renderCn().isBlank(), "条件级差异带可读中文文案");
    }

    @Test
    void 归一化后等价的两个口径不产生差异() {
        // 元素顺序不同但集合相同的旧 / 新两版
        ConditionTree oldNum = ConditionTree.and(
                ConditionTree.leaf("dept_code", "in", List.of("0301", "0302")),
                ConditionTree.leaf("wound_class", "eq", "III"));
        ConditionTree newNum = ConditionTree.and(
                ConditionTree.leaf("wound_class", "eq", "III"),
                ConditionTree.leaf("dept_code", "in", List.of("0302", "0301")));
        MetricDefinition oldDef = def("2026.0", oldNum, List.of());
        MetricDefinition newDef = def("2026.1", newNum, List.of());

        DiffResult normalized = new DiffEngine().diff(oldDef, newDef);
        assertTrue(normalized.isEmpty(),
                "先归一化再比集合：顺序不同但集合相同 → 无任何差异");
        assertEquals(0, normalized.total());
    }

    @Test
    void 归一化次序可证_归一化与不归一化产生不同结果() {
        ConditionTree oldNum = ConditionTree.and(
                ConditionTree.leaf("dept_code", "in", List.of("0301", "0302")),
                ConditionTree.leaf("wound_class", "eq", "III"));
        ConditionTree newNum = ConditionTree.and(
                ConditionTree.leaf("wound_class", "eq", "III"),
                ConditionTree.leaf("dept_code", "in", List.of("0302", "0301")));
        MetricDefinition oldDef = def("2026.0", oldNum, List.of());
        MetricDefinition newDef = def("2026.1", newNum, List.of());

        DiffEngine engine = new DiffEngine();
        DiffResult normalized = engine.diff(oldDef, newDef);
        DiffResult raw = engine.diffWithoutNormalization(oldDef, newDef);

        assertEquals(0, normalized.total(), "先归一化再比集合 → 等价无差异");
        assertTrue(raw.total() > 0, "不归一化直接按原始顺序比 → 顺序不同即产生差异");
        assertNotEquals(normalized.total(), raw.total(), "归一化次序必须能被测试证出");
    }

    @Test
    void 单条件变更只落一个桶() {
        ConditionTree base = ConditionTree.and(
                ConditionTree.leaf("quarter", "eq", "2026Q1"),
                ConditionTree.leaf("dept_code", "in", List.of("0301", "0302")));
        ConditionTree plus = ConditionTree.and(
                ConditionTree.leaf("quarter", "eq", "2026Q1"),
                ConditionTree.leaf("dept_code", "in", List.of("0301", "0302")),
                ConditionTree.leaf("wound_class", "eq", "III"));

        DiffResult d = new DiffEngine().diff(
                def("2026.0", base, List.of()), def("2026.1", plus, List.of()));
        assertEquals(1, d.total());
        assertEquals(1, d.getNumeratorAdded().size());
        assertTrue(d.getExclusionAdded().isEmpty());
        assertTrue(d.getRemoved().isEmpty());
        assertEquals("wound_class", d.getNumeratorAdded().get(0).getField());
    }

    @Test
    void 表达式文本入口先解析再归一化再比集合() {
        DiffEngine engine = new DiffEngine();
        String oldExpr = "dept_code in {0301, 0302} and wound_class eq III";
        String newExpr = "wound_class eq III and dept_code in {0302, 0301}";

        DiffResult normalized = engine.diffExpressions(oldExpr, newExpr);
        assertTrue(normalized.isEmpty(), "解析 → 归一化 → 集合差：等价无差异");
        DiffResult raw = engine.diffExpressionsWithoutNormalization(oldExpr, newExpr);
        assertTrue(raw.total() > 0, "跳过归一化按原始顺序比较：产生差异");
    }
}
