package com.infection.metric.expr;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 任务28：token 化、转义、日期/数字字面量、空白与注释、错误位置。 */
class TokenizerTest {

    private static List<Token> toks(String s) {
        return Tokenizer.tokenize(s);
    }

    @Test
    void 示例表达式token类型与起始位置齐全() {
        List<Token> ts = toks("discharge_date between [2026-01-01, 2026-03-31] and dept_code in {0301, 0302}");
        Set<TokenType> types = ts.stream().map(Token::getType).collect(Collectors.toSet());
        assertTrue(types.contains(TokenType.IDENT));
        assertTrue(types.contains(TokenType.DATE));
        assertTrue(types.contains(TokenType.NUMBER));
        assertTrue(types.contains(TokenType.AND));
        assertTrue(types.contains(TokenType.LBRACKET));
        assertTrue(types.contains(TokenType.RBRACKET));
        assertTrue(types.contains(TokenType.LBRACE));
        assertTrue(types.contains(TokenType.RBRACE));
        assertTrue(types.contains(TokenType.SEP));
        assertEquals(TokenType.EOF, ts.get(ts.size() - 1).getType());

        Token first = ts.get(0);
        assertEquals(TokenType.IDENT, first.getType());
        assertEquals("discharge_date", first.getText());
        assertEquals(1, first.getLine());
        assertEquals(1, first.getCol());
        assertEquals("1:1", first.position());
    }

    @Test
    void 转义引号与字面反斜杠() {
        List<Token> ts = toks("\"a\\\"b\" \"C:\\\\path\"");
        assertEquals(TokenType.STRING, ts.get(0).getType());
        assertEquals("a\"b", ts.get(0).getText());
        assertEquals(1, ts.get(0).getCol());

        assertEquals(TokenType.STRING, ts.get(1).getType());
        assertEquals("C:\\path", ts.get(1).getText());
        assertTrue(ts.get(1).getCol() > ts.get(0).getCol(), "第二个 token 起始列更靠后");
    }

    @Test
    void 未闭合字符串在EOF抛E_LEX_001并指出起始位置() {
        AppException ex = assertThrows(AppException.class, () -> toks("x eq \"未闭合"));
        assertEquals(ErrorCodes.E_LEX_001, ex.getCode());
        assertEquals("1:6", ex.getPosition(), "引号起始于第 1 行第 6 列");
        assertTrue(ex.render().contains("1:6"));
        assertTrue(ex.getHint().contains("引号"));
    }

    @Test
    void 非法字符抛E_LEX_002带位置与实际字符() {
        AppException ex = assertThrows(AppException.class, () -> toks("a @ 1"));
        assertEquals(ErrorCodes.E_LEX_002, ex.getCode());
        assertEquals("1:3", ex.getPosition());
        assertTrue(ex.render().contains("@"));
        assertEquals("@", ex.getContext().get("char"));
    }

    @Test
    void 数字小数与ISO日期字面量且保留前导零() {
        List<Token> ts = toks("n eq 3.5 and d eq 2026-01-01 and z eq 0301");
        Token decimal = ts.stream().filter(t -> t.is(TokenType.NUMBER)).findFirst().orElseThrow();
        assertEquals("3.5", decimal.getText());

        Token date = ts.stream().filter(t -> t.is(TokenType.DATE)).findFirst().orElseThrow();
        assertEquals(TokenType.DATE, date.getType());
        assertEquals("2026-01-01", date.getText());

        Token leading = ts.stream().filter(t -> t.is(TokenType.NUMBER) && t.getText().startsWith("0"))
                .findFirst().orElseThrow();
        assertEquals("0301", leading.getText(), "前导零不被吞掉");
        assertEquals(3, ts.stream().filter(t -> t.is(TokenType.NUMBER) || t.is(TokenType.DATE)).count());
    }

    @Test
    void 空白与注释跳过且换行后行列正确() {
        List<Token> ts = toks("# 注释一行\n  a eq 1 // 行尾注释\n");
        assertEquals(TokenType.IDENT, ts.get(0).getType());
        assertEquals("a", ts.get(0).getText());
        assertEquals(2, ts.get(0).getLine(), "位于第 2 行");
        assertEquals(3, ts.get(0).getCol(), "位于第 3 列");
        assertTrue(ts.stream().noneMatch(t -> t.getText().contains("注释")), "注释内容不产生 token");
        assertEquals(TokenType.EOF, ts.get(ts.size() - 1).getType());
    }
}
