package com.infection.metric.expr;

/**
 * 词法单元：携带 type / text / line / col 四项（任务28）。
 * 行列均从 1 开始，指向 token 的起始字符；{@link #position()} 返回 "行:列"。
 * STRING 类型 token 的 text 为解码后的字符串内容（已处理转义、不含首尾引号）。
 */
public final class Token {

    private final TokenType type;
    private final String text;
    private final int line;
    private final int col;

    public Token(TokenType type, String text, int line, int col) {
        this.type = type;
        this.text = text == null ? "" : text;
        this.line = line;
        this.col = col;
    }

    public TokenType getType() { return type; }
    public String getText() { return text; }
    public int getLine() { return line; }
    public int getCol() { return col; }

    /** token 起始位置，形如 "1:7"。 */
    public String position() { return line + ":" + col; }

    public boolean is(TokenType t) { return type == t; }

    @Override
    public String toString() {
        return type + "(" + text + ")@" + position();
    }
}
