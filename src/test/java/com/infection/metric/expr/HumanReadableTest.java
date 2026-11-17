package com.infection.metric.expr;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务30：条件树 ↔ 中文说明句双向渲染、往返一致、无法识别显式报错。 */
class HumanReadableTest {

    @Test
    void 树到句渲染中文且不堆砌内部字段名() {
        ConditionTree t = ConditionTree.and(
                ConditionTree.leaf("discharge_date", "between", List.of("2026-01-01", "2026-03-31")),
                ConditionTree.leaf("dept_code", "in", List.of("0301", "0302")));
        String s = HumanReadable.render(t);
        assertTrue(s.contains("出院日期在 2026-01-01 至 2026-03-31 之间"), s);
        assertTrue(s.contains("科室代码属于 0301、0302"), s);
        assertTrue(s.contains("，且 "), s);
        assertFalse(s.contains("discharge_date"), "不得出现内部字段名堆砌");
        assertFalse(s.contains("dept_code"));
    }

    @Test
    void 树到句到树往返等价含嵌套括号() {
        ConditionTree t = ConditionTree.and(
                ConditionTree.leaf("discharge_date", "between", List.of("2026-01-01", "2026-03-31")),
                ConditionTree.or(
                        ConditionTree.leaf("dept_code", "in", List.of("0301", "0302")),
                        ConditionTree.leaf("age", "gte", "18")));
        String s = HumanReadable.render(t);
        assertTrue(s.contains("("), "嵌套分支带括号：" + s);
        assertTrue(s.contains("，或 "), s);
        ConditionTree back = HumanReadable.parse(s);
        assertTrue(Canonicalizer.equivalent(t, back), "往返一致：" + s);
        assertEquals(t.declaredFields(), back.declaredFields());
        assertEquals("AND", back.getOp());
    }

    @Test
    void 各比较符中文句式与布尔值还原() {
        assertEquals("科室代码等于 0301",
                HumanReadable.render(ConditionTree.leaf("dept_code", "eq", "0301")));
        assertEquals("科室代码不等于 0301",
                HumanReadable.render(ConditionTree.leaf("dept_code", "ne", "0301")));
        assertEquals("科室代码属于 0301、0302",
                HumanReadable.render(ConditionTree.leaf("dept_code", "in", List.of("0301", "0302"))));
        assertEquals("科室代码不属于 0301",
                HumanReadable.render(ConditionTree.leaf("dept_code", "not_in", List.of("0301"))));
        assertTrue(HumanReadable.render(ConditionTree.leaf("age", "gt", "18")).contains("大于"));
        assertTrue(HumanReadable.render(ConditionTree.leaf("age", "contains", "8")).contains("包含"));
        assertEquals("多重耐药菌标志等于 真",
                HumanReadable.render(ConditionTree.leaf("mdro_flag", "eq", Boolean.TRUE)));
        assertEquals(Boolean.TRUE, HumanReadable.parse("多重耐药菌标志等于 真").getValue());
    }

    @Test
    void 无法识别的句子显式报E_RENDER_001不猜() {
        AppException ex1 = assertThrows(AppException.class,
                () -> HumanReadable.parse("这句话完全看不懂"));
        assertEquals(ErrorCodes.E_RENDER_001, ex1.getCode());
        assertTrue(ex1.getContext().containsKey("reason"));

        AppException ex2 = assertThrows(AppException.class,
                () -> HumanReadable.parse("出院日期大概等于 5"));
        assertEquals(ErrorCodes.E_RENDER_001, ex2.getCode());

        AppException ex3 = assertThrows(AppException.class,
                () -> HumanReadable.parse("a eq 1，且 b eq 2，或 c eq 3"));
        assertEquals(ErrorCodes.E_RENDER_001, ex3.getCode(), "顶层且/或混用不猜优先级");
    }
}
