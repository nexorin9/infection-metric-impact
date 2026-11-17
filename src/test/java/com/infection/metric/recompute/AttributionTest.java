package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.common.ModelTaskPort;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 任务34：规则基线对各类差异给出正确维度；模型不可用时归因仍可用；候选仅作建议。 */
class AttributionTest {

    private static ConditionChange change(ConditionChange.Bucket bucket, TreePosition position, String field) {
        return new ConditionChange(bucket, position, field, "eq", null, "III");
    }

    private static DiffResult diffOf(ConditionChange... changes) {
        List<ConditionChange> n = new ArrayList<>();
        List<ConditionChange> e = new ArrayList<>();
        List<ConditionChange> r = new ArrayList<>();
        for (ConditionChange c : changes) {
            switch (c.getBucket()) {
                case NUMERATOR_ADDED -> n.add(c);
                case EXCLUSION_ADDED -> e.add(c);
                case REMOVED -> r.add(c);
            }
        }
        return new DiffResult(n, e, r);
    }

    /** 覆盖五类位置：分子树 / 分母树 / 排除树 / 适用科室 / 生效期。 */
    private static DiffResult fourKindDiff() {
        return diffOf(
                change(ConditionChange.Bucket.NUMERATOR_ADDED, TreePosition.NUMERATOR, "quarter"),
                change(ConditionChange.Bucket.NUMERATOR_ADDED, TreePosition.DENOMINATOR, "dept_code"),
                change(ConditionChange.Bucket.EXCLUSION_ADDED, TreePosition.EXCLUSION, "wound_class"),
                change(ConditionChange.Bucket.NUMERATOR_ADDED, TreePosition.SCOPE_DEPT, "scope.deptCodes"),
                change(ConditionChange.Bucket.REMOVED, TreePosition.EFFECTIVE_FROM, "effective_from"));
    }

    @Test
    void 规则基线对各类差异给出正确业务维度() {
        List<Attribution> base = new AttributionEngine().ruleBaseline(fourKindDiff());
        Set<BusinessDimension> dims = base.stream()
                .map(Attribution::getDimension).collect(Collectors.toSet());

        assertTrue(dims.contains(BusinessDimension.NUMERATOR_SCOPE), "分子树差异 → 分子范围");
        assertTrue(dims.contains(BusinessDimension.DENOMINATOR_DEFINITION), "分母树差异 → 分母口径");
        assertTrue(dims.contains(BusinessDimension.EXCLUSION_CONDITION), "排除树差异 → 排除条件");
        assertTrue(dims.contains(BusinessDimension.DEPT_SCOPE), "适用科室差异 → 科室范围");
        assertTrue(dims.contains(BusinessDimension.EFFECTIVE_PERIOD), "生效期差异 → 生效期");

        for (Attribution a : base) {
            assertEquals(Attribution.Source.RULE, a.getSource(), "规则通道不依赖模型");
            assertEquals(1.0, a.getConfidence(), 0.0001, "规则结论可信度 1.0");
            assertFalse(a.isPendingHumanConfirmation(), "规则结论无需人工确认");
            assertFalse(a.getEvidenceRef().isBlank(), "规则结论带依据引用");
        }
    }

    @Test
    void 模型不可用_注入null抛错返回null时归因仍可用() {
        DiffResult diff = fourKindDiff();
        List<Attribution> ruleOnly = new AttributionEngine().attribute(diff);
        assertFalse(ruleOnly.isEmpty());
        assertTrue(ruleOnly.stream().allMatch(a -> a.getSource() == Attribution.Source.RULE));

        // ① 端口为 null
        List<Attribution> noPort = new AttributionEngine().attribute(diff);
        assertEquals(ruleOnly.size(), noPort.size());

        // ② 端口抛错
        ModelTaskPort throwing = (task, schema, payload) -> {
            throw new IllegalStateException("模型服务不可达");
        };
        List<Attribution> onError = new AttributionEngine(throwing).attribute(diff);
        assertEquals(ruleOnly.size(), onError.size(), "抛错降级为纯规则结论，不中断业务");
        assertTrue(onError.stream().noneMatch(Attribution::isPendingHumanConfirmation));

        // ③ 端口返回 null
        ModelTaskPort nullResult = (task, schema, payload) -> null;
        List<Attribution> onNull = new AttributionEngine(nullResult).attribute(diff);
        assertEquals(ruleOnly.size(), onNull.size(), "返回 null 同样降级");
    }

    @Test
    void 两通道合并规则优先_模型候选待人工确认并带置信度与依据() {
        ModelTaskPort fake = (task, schema, payload) -> {
            assertEquals("attribute_diff", task, "模型任务固定为 attribute_diff");
            assertNotNull(payload.path("diff_entries"));
            ArrayNode arr = JsonSupport.array();
            ObjectNode one = arr.addObject();
            one.put("dimension", "NUMERATOR_SCOPE");
            one.put("detail", "模型建议：分子范围可能收紧");
            one.put("evidence_ref", "NUMERATOR:quarter:eq");
            one.put("confidence", 0.72);
            return arr;
        };

        List<Attribution> all = new AttributionEngine(fake).attribute(fourKindDiff());
        assertEquals(Attribution.Source.RULE, all.get(0).getSource(), "规则结论优先，排在前面");
        assertTrue(all.stream().anyMatch(a -> a.getSource() == Attribution.Source.RULE
                && a.getDimension() == BusinessDimension.NUMERATOR_SCOPE), "规则结论不被模型覆盖");

        Attribution model = all.stream()
                .filter(a -> a.getSource() == Attribution.Source.MODEL)
                .findFirst().orElseThrow();
        assertTrue(model.isPendingHumanConfirmation(), "模型候选一律标注待人工确认");
        assertEquals(0.72, model.getConfidence(), 0.0001);
        assertEquals("NUMERATOR:quarter:eq", model.getEvidenceRef());
        assertTrue(model.renderCn().contains("待人工确认"));
    }

    @Test
    void 模型返回非法维度或空依据引用时被丢弃() {
        ModelTaskPort bad = (task, schema, payload) -> {
            ArrayNode arr = JsonSupport.array();
            ObjectNode a = arr.addObject();
            a.put("dimension", "NOT_A_DIMENSION");
            a.put("evidence_ref", "NUMERATOR:quarter:eq");
            a.put("confidence", 0.9);
            ObjectNode b = arr.addObject();
            b.put("dimension", "EXCLUSION_CONDITION");
            b.put("evidence_ref", "");   // 空依据引用
            b.put("confidence", 0.8);
            return arr;
        };
        List<Attribution> all = new AttributionEngine(bad).attribute(fourKindDiff());
        assertTrue(all.stream().noneMatch(x -> x.getSource() == Attribution.Source.MODEL),
                "非法维度 / 空依据引用的候选一律不采纳");
        assertFalse(all.isEmpty(), "规则结论仍在");
    }

    @Test
    void 两版口径入口可直接归因() {
        MetricDefinition oldDef = MetricDefinition.builder("MER-TQ-01", "2026.0")
                .numerator(ConditionTree.leaf("quarter", "eq", "2026Q1"))
                .denominator(ConditionTree.leaf("quarter", "eq", "2026Q1"))
                .status(MetricStatus.PUBLISHED).build();
        MetricDefinition newDef = MetricDefinition.builder("MER-TQ-01", "2026.1")
                .numerator(ConditionTree.and(
                        ConditionTree.leaf("quarter", "eq", "2026Q1"),
                        ConditionTree.leaf("wound_class", "eq", "III")))
                .denominator(ConditionTree.leaf("quarter", "eq", "2026Q1"))
                .status(MetricStatus.PUBLISHED).build();

        List<Attribution> result = new AttributionEngine().attribute(oldDef, newDef);
        assertTrue(result.stream().anyMatch(a -> a.getDimension() == BusinessDimension.NUMERATOR_SCOPE));
    }
}
