package com.infection.metric.expr;

import com.infection.metric.common.AppException;
import com.infection.metric.common.DataRecord;
import com.infection.metric.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务32：预编译计划、短路正确性、1200×8 性能、字段缺失与空值语义。 */
class EvalPerfTest {

    private static ConditionTree eightLeafTree() {
        return ConditionTree.and(
                ConditionTree.leaf("f1", "gte", 1),
                ConditionTree.leaf("f2", "gte", 1),
                ConditionTree.leaf("f3", "gte", 1),
                ConditionTree.leaf("f4", "gte", 1),
                ConditionTree.leaf("f5", "gte", 1),
                ConditionTree.leaf("f6", "gte", 1),
                ConditionTree.leaf("f7", "gte", 1),
                ConditionTree.leaf("f8", "gte", 1));
    }

    private static DataRecord rec(int base) {
        DataRecord.Builder b = DataRecord.builder();
        for (int i = 1; i <= 8; i++) {
            b.put("f" + i, base + i);
        }
        return b.build();
    }

    @Test
    void 单指标批量求值1200乘8叶子不超过200毫秒() {
        ConditionTree tree = eightLeafTree();
        CompiledPlan plan = CompiledPlan.compile(tree, "M001", "EMR");
        assertEquals(8, plan.leafCount());

        List<DataRecord> records = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            records.add(rec(i % 5));
        }
        plan.evalAll(records); // JIT 预热
        plan.evalAll(records);

        long start = System.nanoTime();
        int matched = plan.evalAll(records);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms <= 200, "实测 " + ms + "ms 应 ≤ 200ms");
        assertEquals(1200, matched, "全部记录命中");
    }

    @Test
    void 短路结果与非短路全量基准一致() {
        ConditionTree tree = eightLeafTree();
        CompiledPlan plan = CompiledPlan.compile(tree, "M001", "EMR");
        for (int base : new int[]{0, 1, -5, 3, 100}) {
            DataRecord r = rec(base);
            boolean reference = CompiledPlan.referenceEval(tree, r,
                    Evaluator.NullPolicy.FAIL, "M001", "EMR");
            assertEquals(reference, plan.eval(r), "base=" + base + " 短路与全量基准一致");
        }
    }

    @Test
    void AND任一假与OR任一真短路且求值计数可见() {
        DataRecord r = rec(0); // f1=1

        ConditionTree andTree = ConditionTree.and(
                ConditionTree.leaf("f1", "gte", 100), // 假
                ConditionTree.leaf("f2", "gte", 1),
                ConditionTree.leaf("f3", "gte", 1),
                ConditionTree.leaf("f4", "gte", 1));
        CompiledPlan andPlan = CompiledPlan.compile(andTree, "M001", "EMR");
        assertFalse(andPlan.eval(r));
        assertEquals(1, andPlan.lastLeafEvals(), "AND 第一个假即短路，后续叶子不求值");

        ConditionTree orTree = ConditionTree.or(
                ConditionTree.leaf("f1", "gte", 1), // 真
                ConditionTree.leaf("f2", "gte", 100),
                ConditionTree.leaf("f3", "gte", 100),
                ConditionTree.leaf("f4", "gte", 100));
        CompiledPlan orPlan = CompiledPlan.compile(orTree, "M001", "EMR");
        assertTrue(orPlan.eval(r));
        assertEquals(1, orPlan.lastLeafEvals(), "OR 第一个真即短路");
    }

    @Test
    void 批量求值字段缺失显式失败不按false() {
        CompiledPlan plan = CompiledPlan.compile(eightLeafTree(), "M001", "EMR");
        DataRecord missing = DataRecord.builder().put("f1", 5).build();
        AppException ex = assertThrows(AppException.class, () -> plan.eval(missing));
        assertEquals(ErrorCodes.E_FIELD_001, ex.getCode());
        assertTrue(ex.render().contains("f2"));

        List<DataRecord> batch = new ArrayList<>();
        batch.add(rec(0));
        batch.add(missing);
        AppException batchEx = assertThrows(AppException.class, () -> plan.evalAll(batch));
        assertEquals(ErrorCodes.E_FIELD_001, batchEx.getCode());
    }

    @Test
    void 空值按口径显式声明语义处理() {
        ConditionTree tree = ConditionTree.leaf("f1", "gte", 1);
        DataRecord blank = DataRecord.builder().put("f1", "").build();

        CompiledPlan failPlan = CompiledPlan.compile(tree,
                Evaluator.NullPolicy.FAIL, "M001", "EMR");
        AppException ex = assertThrows(AppException.class, () -> failPlan.eval(blank));
        assertEquals(ErrorCodes.E_DATA_001, ex.getCode());

        CompiledPlan noMatchPlan = CompiledPlan.compile(tree,
                Evaluator.NullPolicy.NO_MATCH, "M001", "EMR");
        assertFalse(noMatchPlan.eval(blank), "显式声明「空值不计入」时该条件为假");
    }
}
