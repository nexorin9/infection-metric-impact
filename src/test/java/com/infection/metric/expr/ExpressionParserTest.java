package com.infection.metric.expr;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务5：递归下降解析器、条件树往返、行列位置与错误码、边界用例。 */
class ExpressionParserTest {

    @Test
    void 解析示例口径并完成文本与JSON双向往返() {
        String text = "discharge_date between [2026-01-01, 2026-03-31] and dept_code in {0301, 0302}";
        ConditionTree t = ExpressionParser.parse(text);

        assertFalse(t.isLeaf(), "顶层应为 AND 分支");
        assertEquals("AND", t.getOp());
        assertEquals(2, t.getChildren().size(), "两个子条件");

        // 文本序列化后再解析应等价
        String again = ExpressionParser.toExpression(t);
        ConditionTree t2 = ExpressionParser.parse(again);
        assertTrue(Canonicalizer.equivalent(t, t2), "文本往返等价：" + again);

        // JSON 序列化后再解析应等价（显式用到 Jackson）
        ConditionTree t3 = ConditionTree.fromJson(t.toJson().toString());
        assertTrue(Canonicalizer.equivalent(t, t3));
        JsonNode json = t.toJson();
        assertEquals("AND", json.path("op").asText());
        assertEquals(2, json.path("children").size());
        assertTrue(JsonSupport.parse(t.canonicalJson()).isObject(), "canonical JSON 可被 Jackson 解析");

        // 带前导零的科室代码按字面保留
        ConditionTree dept = t.getChildren().get(1);
        assertEquals("dept_code", dept.getField());
        assertEquals(List.of("0301", "0302"), dept.getValue());
    }

    @Test
    void 括号与or优先级正确且嵌套往返等价() {
        ConditionTree t = ExpressionParser.parse("(a eq 1 or b eq 2) and c eq 3");
        assertEquals("AND", t.getOp());
        assertEquals(2, t.getChildren().size());
        assertEquals("OR", t.getChildren().get(0).getOp(), "括号内为 OR");
        assertEquals("c", t.getChildren().get(1).getField());

        String again = ExpressionParser.toExpression(t);
        assertTrue(again.contains("("), "嵌套分支应带括号：" + again);
        assertTrue(Canonicalizer.equivalent(t, ExpressionParser.parse(again)));
    }

    @Test
    void 全部比较符与标量类型布尔及空集合解析() {
        for (String cmp : List.of("eq", "ne", "gte", "lte", "gt", "lt", "contains")) {
            ConditionTree leaf = ExpressionParser.parse("f " + cmp + " \"abc\"");
            assertTrue(leaf.isLeaf());
            assertEquals(cmp, leaf.getCmp());
            assertEquals("abc", leaf.getValue());
        }
        assertEquals(Boolean.TRUE, ExpressionParser.parse("flag eq true").getValue());
        assertEquals(Boolean.FALSE, ExpressionParser.parse("flag eq false").getValue());
        assertEquals("3.5", ExpressionParser.parse("score gte 3.5").getValue());
        assertEquals(List.of("1", "10"), ExpressionParser.parse("age between [1, 10]").getValue());
        assertEquals(0, ((List<?>) ExpressionParser.parse("d in {}").getValue()).size(),
                "空集合不当解析错误");
        assertEquals(0, ((List<?>) ExpressionParser.parse("d not_in {}").getValue()).size());
    }

    @Test
    void 语法错误带行列位置与对应错误码() {
        AppException e1 = assertThrows(AppException.class, () -> ExpressionParser.parse(""));
        assertEquals(ErrorCodes.E_PARSE_003, e1.getCode(), "空串");
        assertEquals("1:1", e1.getPosition());

        AppException e2 = assertThrows(AppException.class,
                () -> ExpressionParser.parse("(a eq 1 and b eq 2"));
        assertEquals(ErrorCodes.E_PARSE_001, e2.getCode(), "缺右括号");
        assertNotNull(e2.getPosition());
        assertTrue(e2.render().contains("右括号"));

        AppException e3 = assertThrows(AppException.class,
                () -> ExpressionParser.parse("dept_code in {0301, 0302"));
        assertEquals(ErrorCodes.E_PARSE_003, e3.getCode(), "缺花括号");
        assertNotNull(e3.getPosition());
        assertTrue(e3.render().contains("dept_code"));

        AppException e4 = assertThrows(AppException.class,
                () -> ExpressionParser.parse("dept_code like 0301"));
        assertEquals(ErrorCodes.E_PARSE_002, e4.getCode(), "未知比较符");
        assertTrue(e4.render().contains("like"));
        assertNotNull(e4.getPosition());

        AppException e5 = assertThrows(AppException.class,
                () -> ExpressionParser.parse("a eq {1, 2}"));
        assertEquals(ErrorCodes.E_TYPE_002, e5.getCode(), "标量位置收到花括号");
        assertNotNull(e5.getPosition());
    }

    @Test
    void 转义引号与字面反斜杠解析并往返等价() {
        ConditionTree q = ExpressionParser.parse("note eq \"he said \\\"hi\\\"\"");
        assertEquals("he said \"hi\"", q.getValue());

        ConditionTree b = ExpressionParser.parse("path eq \"C:\\\\temp\"");
        assertEquals("C:\\temp", b.getValue());

        assertTrue(Canonicalizer.equivalent(q,
                ExpressionParser.parse(ExpressionParser.toExpression(q))));
        assertTrue(Canonicalizer.equivalent(b,
                ExpressionParser.parse(ExpressionParser.toExpression(b))));
    }
}
