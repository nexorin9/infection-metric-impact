package com.infection.metric.expr;

import com.infection.metric.common.ErrorCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 口径表达式递归下降解析器（任务5）：文本 ↔ {@link ConditionTree} 往返。
 *
 * <p>支持形如
 * {@code discharge_date between [2026-01-01, 2026-03-31] and dept_code in {0301, 0302}}
 * 的口径表达式，以及括号 (...)、or、eq/ne/gte/lte/gt/lt/contains、引号字符串、
 * 数值、布尔、转义引号、字面反斜杠、空集合 {}。</p>
 *
 * <p>语法（递归下降）：</p>
 * <pre>
 *   expr    := orExpr
 *   orExpr  := andExpr (OR andExpr)*
 *   andExpr := primary (AND primary)*
 *   primary := '(' expr ')' | condition
 *   condition := IDENT cmp value
 *   value   := '[' scalar ',' scalar ']'        (between)
 *            | '{' (scalar (',' scalar)*)? '}'  (in / not_in)
 *            | scalar                           (其余比较符)
 * </pre>
 *
 * <p>语法错误一律抛 {@link com.infection.metric.common.AppException} 并带行列位置与错误码：
 * E_PARSE_001 期望右括号、E_PARSE_002 未知比较符、E_PARSE_003 括号/花括号不匹配、
 * E_TYPE_002 字段值类型不符（用 {@code .at(line,col)}）。</p>
 *
 * <p>标量值在解析结果中统一保留为 String（NUMBER/DATE/裸 IDENT）或 Boolean（true/false），
 * 以保留科室代码 0301 等带前导零编码的字面语义；{@link #toExpression(ConditionTree)}
 * 与 parse 严格互逆，保证「序列化后再解析应等价」。</p>
 */
public final class ExpressionParser {

    private static final Pattern BARE_SCALAR = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_./+]*");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Set<String> RESERVED = Set.of(
            "and", "or", "true", "false",
            "eq", "ne", "in", "not_in", "between", "gte", "lte", "gt", "lt", "contains");

    private final List<Token> tokens;
    private int pos = 0;
    private String lastField = "(未指定)";

    private ExpressionParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** 文本 → 条件树。 */
    public static ConditionTree parse(String text) {
        return new ExpressionParser(Tokenizer.tokenize(text)).parseAll();
    }

    /** parse 的别名，语义更直观。 */
    public static ConditionTree fromText(String text) {
        return parse(text);
    }

    /** 条件树 → 文本（与 parse 互逆，供往返等价使用）。 */
    public static String toExpression(ConditionTree tree) {
        if (tree == null) {
            throw ExpressionErrors.error(ErrorCodes.E_PARSE_003, "field", "(空条件树)");
        }
        StringBuilder sb = new StringBuilder();
        writeNode(tree, sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- 解析

    private ConditionTree parseAll() {
        Token first = peek();
        if (first.is(TokenType.EOF)) {
            throw ExpressionErrors.errorAt(ErrorCodes.E_PARSE_003, first.getLine(), first.getCol(),
                    "field", lastField, "reason", "空表达式");
        }
        ConditionTree tree = parseOr();
        Token tail = peek();
        if (!tail.is(TokenType.EOF)) {
            throw ExpressionErrors.errorAt(ErrorCodes.E_PARSE_003, tail.getLine(), tail.getCol(),
                    "field", lastField, "token", describe(tail),
                    "reason", "表达式结尾存在多余内容或右括号不匹配");
        }
        return tree;
    }

    private ConditionTree parseOr() {
        List<ConditionTree> parts = new ArrayList<>();
        parts.add(parseAnd());
        while (peek().is(TokenType.OR)) {
            consume();
            parts.add(parseAnd());
        }
        return parts.size() == 1 ? parts.get(0) : ConditionTree.branch("OR", parts);
    }

    private ConditionTree parseAnd() {
        List<ConditionTree> parts = new ArrayList<>();
        parts.add(parsePrimary());
        while (peek().is(TokenType.AND)) {
            consume();
            parts.add(parsePrimary());
        }
        return parts.size() == 1 ? parts.get(0) : ConditionTree.branch("AND", parts);
    }

    private ConditionTree parsePrimary() {
        Token t = peek();
        if (t.is(TokenType.OP) && "(".equals(t.getText())) {
            consume();
            ConditionTree inner = parseOr();
            expectOp(")");
            return inner;
        }
        return parseCondition();
    }

    private ConditionTree parseCondition() {
        Token fieldTok = expectIdent("字段名");
        String field = fieldTok.getText();
        lastField = field;

        Token cmpTok = expectIdent("比较符");
        String cmp = cmpTok.getText();
        if (!ConditionTree.CMPS.contains(cmp)) {
            throw ExpressionErrors.errorAt(ErrorCodes.E_PARSE_002, cmpTok.getLine(), cmpTok.getCol(),
                    "cmp", cmp, "field", field);
        }
        Object value = parseValue(cmp, field);
        return ConditionTree.leaf(field, cmp, value);
    }

    private Object parseValue(String cmp, String field) {
        switch (cmp) {
            case "between": {
                expect(TokenType.LBRACKET, "[", field);
                Object lo = parseScalar(field);
                expect(TokenType.SEP, ",", field);
                Object hi = parseScalar(field);
                expect(TokenType.RBRACKET, "]", field);
                return List.of(lo, hi);
            }
            case "in":
            case "not_in": {
                expect(TokenType.LBRACE, "{", field);
                List<Object> elems = new ArrayList<>();
                if (!peek().is(TokenType.RBRACE)) {
                    elems.add(parseScalar(field));
                    while (peek().is(TokenType.SEP)) {
                        consume();
                        elems.add(parseScalar(field));
                    }
                }
                expect(TokenType.RBRACE, "}", field);
                return elems;
            }
            default:
                return parseScalar(field);
        }
    }

    private Object parseScalar(String field) {
        Token t = peek();
        if (t.is(TokenType.STRING)) {
            consume();
            return t.getText();
        }
        if (t.is(TokenType.NUMBER) || t.is(TokenType.DATE)) {
            consume();
            return t.getText();
        }
        if (t.is(TokenType.IDENT)) {
            consume();
            String w = t.getText();
            if ("true".equalsIgnoreCase(w)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(w)) return Boolean.FALSE;
            return w;
        }
        throw ExpressionErrors.errorAt(ErrorCodes.E_TYPE_002, t.getLine(), t.getCol(),
                "field", field, "cmp", "(标量)",
                "expected", "标量值（字符串/数值/布尔）", "actual", describe(t));
    }

    private void expectOp(String symbol) {
        Token t = peek();
        if (t.is(TokenType.OP) && symbol.equals(t.getText())) {
            consume();
            return;
        }
        throw ExpressionErrors.errorAt(ErrorCodes.E_PARSE_001, t.getLine(), t.getCol(),
                "field", lastField, "expected", symbol, "actual", describe(t));
    }

    private void expect(TokenType type, String symbol, String field) {
        Token t = peek();
        if (t.is(type)) {
            consume();
            return;
        }
        throw ExpressionErrors.errorAt(ErrorCodes.E_PARSE_003, t.getLine(), t.getCol(),
                "field", field, "expected", symbol, "actual", describe(t));
    }

    private Token expectIdent(String what) {
        Token t = peek();
        if (t.is(TokenType.IDENT)) {
            consume();
            return t;
        }
        throw ExpressionErrors.errorAt(ErrorCodes.E_PARSE_003, t.getLine(), t.getCol(),
                "field", lastField, "expected", what, "actual", describe(t));
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token consume() {
        return tokens.get(pos++);
    }

    private static String describe(Token t) {
        return t.is(TokenType.EOF) ? "输入结束" : t.getText();
    }

    // ---------------------------------------------------------------- 序列化

    private static void writeNode(ConditionTree node, StringBuilder sb) {
        if (node.isLeaf()) {
            sb.append(node.getField()).append(' ').append(node.getCmp()).append(' ');
            writeValue(node.getCmp(), node.getValue(), sb);
            return;
        }
        String glue = "AND".equals(node.getOp()) ? " and " : " or ";
        List<ConditionTree> kids = node.getChildren();
        for (int i = 0; i < kids.size(); i++) {
            if (i > 0) sb.append(glue);
            ConditionTree kid = kids.get(i);
            boolean paren = !kid.isLeaf();
            if (paren) sb.append('(');
            writeNode(kid, sb);
            if (paren) sb.append(')');
        }
    }

    private static void writeValue(String cmp, Object value, StringBuilder sb) {
        if ("between".equals(cmp)) {
            sb.append('[');
            if (value instanceof List<?> l) {
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(", ");
                    writeScalar(l.get(i), sb);
                }
            } else {
                writeScalar(value, sb);
            }
            sb.append(']');
        } else if ("in".equals(cmp) || "not_in".equals(cmp) || value instanceof List<?>) {
            sb.append('{');
            if (value instanceof List<?> l) {
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(", ");
                    writeScalar(l.get(i), sb);
                }
            }
            sb.append('}');
        } else {
            writeScalar(value, sb);
        }
    }

    private static void writeScalar(Object v, StringBuilder sb) {
        if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
            return;
        }
        if (v instanceof Number n) {
            sb.append(n);
            return;
        }
        String s = String.valueOf(v);
        boolean bare = ISO_DATE.matcher(s).matches()
                || (BARE_SCALAR.matcher(s).matches() && !RESERVED.contains(s.toLowerCase()));
        if (bare) {
            sb.append(s);
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\t': sb.append("\\t"); break;
                case '\r': sb.append("\\r"); break;
                default:   sb.append(c);
            }
        }
        sb.append('"');
    }
}
