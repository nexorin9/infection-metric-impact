package com.infection.metric.expr;

import com.infection.metric.common.AppException;
import com.infection.metric.common.DataRecord;
import com.infection.metric.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务29：between/in/not_in/contains 边界语义（不少于 10 条判定用例）。 */
class SetSemanticsTest {

    @Test
    void between闭区间含端点且上下界相等成立() {
        assertTrue(SetSemantics.between(1, List.of(1, 10)), "下界含");
        assertTrue(SetSemantics.between(10, List.of(1, 10)), "上界含");
        assertTrue(SetSemantics.between(5, List.of(1, 10)), "区间内");
        assertFalse(SetSemantics.between(11, List.of(1, 10)), "区间外");
        assertTrue(SetSemantics.between(7, List.of(7, 7)), "lo==hi 成立");
        assertFalse(SetSemantics.between(8, List.of(7, 7)));
    }

    @Test
    void between上下界颠倒抛E_SEM_001() {
        AppException ex = assertThrows(AppException.class,
                () -> SetSemantics.between(5, List.of(10, 1)));
        assertEquals(ErrorCodes.E_SEM_001, ex.getCode());
        assertTrue(ex.render().contains("区间") || ex.render().contains("颠倒"));
    }

    @Test
    void 数值区间与字符串区间分别处理且混排抛E_SEM_002() {
        assertTrue(SetSemantics.between(50, List.of(1, 100)), "数值区间");
        assertTrue(SetSemantics.between("2026-02-01", List.of("2026-01-01", "2026-03-31")),
                "字符串/日期区间");
        assertFalse(SetSemantics.between("2026-04-01", List.of("2026-01-01", "2026-03-31")));
        AppException ex = assertThrows(AppException.class,
                () -> SetSemantics.between(5, List.of(1, "abc")));
        assertEquals(ErrorCodes.E_SEM_002, ex.getCode(), "数值与字符串混排");
    }

    @Test
    void in集合成员判定且空集合恒假() {
        assertTrue(SetSemantics.in("0301", List.of("0301", "0302")));
        assertFalse(SetSemantics.in("0303", List.of("0301", "0302")));
        assertFalse(SetSemantics.in("301", List.of("0301", "0302")), "带前导零编码按字面区分");
        assertTrue(SetSemantics.in(2, List.of(1, 2, 3)), "数值成员");
        assertFalse(SetSemantics.in("x", List.of()), "in {} 恒假");
        AppException ex = assertThrows(AppException.class,
                () -> SetSemantics.in("x", List.of(1, "abc")));
        assertEquals(ErrorCodes.E_SEM_002, ex.getCode(), "集合内类型混排");
    }

    @Test
    void not_in非成员判定且空集合恒真() {
        assertTrue(SetSemantics.notIn("0303", List.of("0301", "0302")));
        assertFalse(SetSemantics.notIn("0301", List.of("0301", "0302")));
        assertTrue(SetSemantics.notIn("x", List.of()), "not_in {} 恒真");
    }

    @Test
    void 空集合不得被当成解析错误且求值恒假恒真() {
        ConditionTree inEmpty = assertDoesNotThrow(() -> ExpressionParser.parse("dept_code in {}"));
        assertEquals(0, ((List<?>) inEmpty.getValue()).size());
        ConditionTree notInEmpty = assertDoesNotThrow(() -> ExpressionParser.parse("dept_code not_in {}"));
        assertEquals(0, ((List<?>) notInEmpty.getValue()).size());
        DataRecord rec = DataRecord.builder().put("dept_code", "0301").build();
        assertFalse(Evaluator.eval(inEmpty, rec), "in {} 恒假");
        assertTrue(Evaluator.eval(notInEmpty, rec), "not_in {} 恒真");
    }

    @Test
    void contains子串判定且空串视为包含() {
        assertTrue(SetSemantics.contains("感染性发热", "发热"));
        assertFalse(SetSemantics.contains("感染性发热", "腹泻"));
        assertTrue(SetSemantics.contains("任意内容", ""), "空串视为包含");
        assertTrue(SetSemantics.contains("", ""), "空串包含空串");
    }

    @Test
    void 六个标量比较符判定() {
        assertTrue(SetSemantics.test("eq", 10, 10));
        assertTrue(SetSemantics.test("ne", 11, 10));
        assertTrue(SetSemantics.test("gte", 10, 10));
        assertTrue(SetSemantics.test("lte", 10, 10));
        assertTrue(SetSemantics.test("gt", 11, 10));
        assertTrue(SetSemantics.test("lt", 9, 10));
        assertFalse(SetSemantics.test("gt", 9, 10));
        assertTrue(SetSemantics.test("eq", "感染", "感染"));
        assertFalse(SetSemantics.test("eq", "a", "b"));
    }
}
