package com.infection.metric.expr;

import com.infection.metric.common.AppException;
import com.infection.metric.common.DataRecord;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.FieldDef;
import com.infection.metric.common.FieldDictionary;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 任务27：统一异常结构与每类错误码的触发用例、位置与字段名文案。 */
class ErrorCodeTest {

    private static AppException expect(String code, Runnable action) {
        AppException ex = assertThrows(AppException.class, action::run);
        assertEquals(code, ex.getCode());
        return ex;
    }

    private static FieldDictionary numberDict() {
        return new FieldDictionary("v1", List.of(
                new FieldDef("age", "number", "encounter", true, "EMR"),
                new FieldDef("dept_code", "string", "encounter", true, "EMR")));
    }

    @Test
    void 解析类错误码且文案含位置与字段名() {
        AppException e1 = expect(ErrorCodes.E_PARSE_001,
                () -> ExpressionParser.parse("(a eq 1 and b eq 2"));
        assertTrue(e1.render().contains("右括号"));
        assertNotNull(e1.getPosition());

        AppException e2 = expect(ErrorCodes.E_PARSE_002,
                () -> ExpressionParser.parse("dept_code like 0301"));
        assertTrue(e2.render().contains("dept_code"), "文案含字段名");
        assertTrue(e2.render().contains("like"), "文案含实际比较符");
        assertNotNull(e2.getPosition());
        assertTrue(e2.render().contains(e2.getPosition()), "文案含具体位置");

        AppException e3 = expect(ErrorCodes.E_PARSE_003,
                () -> ExpressionParser.parse("dept_code in {1, 2"));
        assertTrue(e3.render().contains("dept_code"));
        assertNotNull(e3.getPosition());
    }

    @Test
    void 词法类错误码含起始位置与实际字符() {
        AppException e1 = expect(ErrorCodes.E_LEX_001, () -> Tokenizer.tokenize("\"未闭合"));
        assertTrue(e1.render().contains("1:1"), "未闭合串指出起始位置");
        assertEquals("1:1", e1.getPosition());
        assertTrue(e1.getHint().contains("引号"));

        AppException e2 = expect(ErrorCodes.E_LEX_002, () -> Tokenizer.tokenize("a @ 1"));
        assertTrue(e2.render().contains("@"), "含实际字符");
        assertEquals("1:3", e2.getPosition());
        assertEquals("@", e2.getContext().get("char"));
    }

    @Test
    void 类型字段与数据类错误码含字段名() {
        AppException e1 = expect(ErrorCodes.E_TYPE_002,
                () -> TypeChecker.check(ConditionTree.leaf("dept_code", "between", "不是列表")));
        assertTrue(e1.render().contains("dept_code"));
        assertTrue(e1.render().contains("between"));

        AppException e2 = expect(ErrorCodes.E_TYPE_003,
                () -> new FieldTypeChecker(numberDict(), "EMR")
                        .check(ConditionTree.leaf("age", "eq", "abc"), "M001"));
        assertTrue(e2.render().contains("age"));
        assertTrue(e2.render().contains("数值"));

        AppException e3 = expect(ErrorCodes.E_FIELD_001,
                () -> new FieldValidator().validate(
                        ConditionTree.leaf("missing_field", "eq", "1"),
                        DataRecord.builder().put("other", "x").build(), "M001", "EMR"));
        assertTrue(e3.render().contains("M001"));
        assertTrue(e3.render().contains("missing_field"));
        assertTrue(e3.render().contains("EMR"));

        AppException e4 = expect(ErrorCodes.E_DATA_001,
                () -> DataRecord.builder().build().get("x"));
        assertTrue(e4.render().contains("x"));

        AppException e5 = expect(ErrorCodes.E_DATA_002,
                () -> new FieldValidator(4).validateDirty(
                        DataRecord.builder().put("note", "超长内容12345").build()));
        assertTrue(e5.render().contains("note"));
        assertTrue(e5.getContext().containsKey("limit"));
    }

    @Test
    void 语义与渲染类错误码() {
        AppException e1 = expect(ErrorCodes.E_SEM_001,
                () -> SetSemantics.between(5, List.of(10, 1)));
        assertTrue(e1.render().contains("区间"));

        AppException e2 = expect(ErrorCodes.E_SEM_002,
                () -> SetSemantics.in("x", List.of(1, "abc")));
        assertNotNull(e2.getHint());

        AppException e3 = expect(ErrorCodes.E_RENDER_001,
                () -> HumanReadable.parse("这句话完全无法识别"));
        assertTrue(e3.render().contains("无法识别") || e3.getContext().containsKey("reason"));
    }

    @Test
    void 文案渲染复用ErrorCodes并带上下文变量() {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("code", "M001");
        ctx.put("field", "dept_code");
        ctx.put("source", "EMR");
        String rendered = ExpressionErrors.renderWithContext(ErrorCodes.E_FIELD_001, ctx);
        assertEquals(ErrorCodes.render(ErrorCodes.E_FIELD_001, ctx), rendered);
        assertTrue(rendered.contains("M001"));
        assertTrue(rendered.contains("dept_code"));
        assertTrue(rendered.contains("EMR"));

        AppException ex = ExpressionErrors.error(ErrorCodes.E_FIELD_001, ctx);
        assertEquals("M001", ex.getContext().get("code"));
        assertNotNull(ex.getHint());
        assertTrue(ex.render().contains("dept_code"));
        assertTrue(ex.render().contains("未提供该字段"));
    }
}
