package com.infection.metric.expr;

import com.infection.metric.common.AppException;
import com.infection.metric.common.DataRecord;
import com.infection.metric.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 任务6：类型检查、全部比较符求值、AND/OR 短路、字段存在性与脏数据边界。 */
class ExpressionEvalTest {

    @Test
    void 类型不匹配抛E_TYPE_002() {
        ConditionTree badBetween = ConditionTree.leaf("age", "between", "不是列表");
        AppException ex = assertThrows(AppException.class,
                () -> Evaluator.eval(badBetween, DataRecord.builder().put("age", 5).build()));
        assertEquals(ErrorCodes.E_TYPE_002, ex.getCode());
        assertTrue(ex.render().contains("age"));

        AppException ex2 = assertThrows(AppException.class,
                () -> Evaluator.eval(ConditionTree.leaf("dept", "in", "不是列表"),
                        DataRecord.builder().put("dept", "x").build()));
        assertEquals(ErrorCodes.E_TYPE_002, ex2.getCode());
        assertTrue(ex2.render().contains("dept"));
    }

    @Test
    void 字段缺失显式失败且文案带口径编码与数据源() {
        ConditionTree t = ConditionTree.leaf("dept_code", "eq", "0301");
        DataRecord rec = DataRecord.builder().put("age", 30).build(); // 缺 dept_code
        AppException ex = assertThrows(AppException.class,
                () -> Evaluator.eval(t, rec, "M001", "EMR"));
        assertEquals(ErrorCodes.E_FIELD_001, ex.getCode());
        assertTrue(ex.render().contains("M001"));
        assertTrue(ex.render().contains("dept_code"));
        assertTrue(ex.render().contains("EMR"));
        assertTrue(ex.render().contains("未提供该字段"));
    }

    @Test
    void 空值显式失败或按口径声明语义处理不得静默按0() {
        ConditionTree t = ConditionTree.leaf("dept_code", "eq", "0301");
        DataRecord blank = DataRecord.builder().put("dept_code", "").build();
        AppException ex = assertThrows(AppException.class,
                () -> Evaluator.eval(t, blank, Evaluator.NullPolicy.FAIL));
        assertEquals(ErrorCodes.E_DATA_001, ex.getCode());
        assertFalse(Evaluator.eval(t, blank, Evaluator.NullPolicy.NO_MATCH), "显式声明空值不计入");
        assertTrue(Evaluator.eval(t,
                DataRecord.builder().put("dept_code", "0301").build(),
                Evaluator.NullPolicy.FAIL), "合法取值正常命中");
    }

    @Test
    void 编码混编与超长字段显式失败() {
        ConditionTree t = ConditionTree.leaf("note", "eq", "x");
        DataRecord mojibake = DataRecord.builder().put("note", "乱码\ufffd混编").build();
        AppException ex = assertThrows(AppException.class,
                () -> Evaluator.eval(t, mojibake, Evaluator.NullPolicy.FAIL));
        assertEquals(ErrorCodes.E_DATA_001, ex.getCode());
        assertTrue(ex.render().contains("编码混编") || ex.getContext().getOrDefault("reason", "").contains("编码混编"));

        DataRecord tooLong = DataRecord.builder().put("note", "x".repeat(300)).build();
        AppException ex2 = assertThrows(AppException.class,
                () -> Evaluator.eval(t, tooLong, Evaluator.NullPolicy.FAIL));
        assertEquals(ErrorCodes.E_DATA_002, ex2.getCode());
        assertTrue(ex2.render().contains("note"));
    }

    @Test
    void 求值器支持全部10个比较符() {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("v", 5);
        rec.put("s", "abcdef");
        rec.put("code", "A");
        assertTrue(Evaluator.eval(ConditionTree.leaf("v", "eq", 5), rec));
        assertTrue(Evaluator.eval(ConditionTree.leaf("v", "ne", 6), rec));
        assertTrue(Evaluator.eval(ConditionTree.leaf("v", "gte", 5), rec));
        assertTrue(Evaluator.eval(ConditionTree.leaf("v", "lte", 5), rec));
        assertTrue(Evaluator.eval(ConditionTree.leaf("v", "gt", 4), rec));
        assertTrue(Evaluator.eval(ConditionTree.leaf("v", "lt", 6), rec));
        assertTrue(Evaluator.eval(ConditionTree.leaf("v", "between", List.of(1, 10)), rec));
        assertTrue(Evaluator.eval(ConditionTree.leaf("code", "in", List.of("A", "B")), rec));
        assertTrue(Evaluator.eval(ConditionTree.leaf("code", "not_in", List.of("C")), rec));
        assertTrue(Evaluator.eval(ConditionTree.leaf("s", "contains", "cd"), rec));
        assertEquals(10, ConditionTree.CMPS.size());
    }

    @Test
    void AND与OR短路逻辑正确且Map记录可求值() {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("age", 35);
        rec.put("dept", "0301");
        rec.put("note", "感染性发热");
        assertTrue(Evaluator.eval(ConditionTree.leaf("age", "gte", 18), rec));
        assertTrue(Evaluator.eval(ConditionTree.leaf("dept", "in", List.of("0301", "0302")), rec));
        assertTrue(Evaluator.eval(ConditionTree.leaf("note", "contains", "发热"), rec));
        assertFalse(Evaluator.eval(ConditionTree.leaf("age", "lt", 18), rec));

        ConditionTree and = ConditionTree.and(
                ConditionTree.leaf("age", "gte", 18),
                ConditionTree.leaf("dept", "eq", "0301"));
        assertTrue(Evaluator.eval(and, rec));
        ConditionTree or = ConditionTree.or(
                ConditionTree.leaf("age", "lt", 18),
                ConditionTree.leaf("dept", "eq", "0301"));
        assertTrue(Evaluator.eval(or, rec));
        assertFalse(Evaluator.eval(ConditionTree.and(
                ConditionTree.leaf("age", "gte", 18),
                ConditionTree.leaf("dept", "eq", "9999")), rec));
    }
}
