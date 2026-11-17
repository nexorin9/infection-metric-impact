package com.infection.metric.expr;

/**
 * 口径表达式词法单元类型（任务28）。
 * 覆盖标识符、数值、字符串、比较/括号符号、集合括号、分隔符、ISO 日期、
 * 逻辑连接词与结束符。括号 ( ) 归入 {@link #OP}（text 即 "(" 或 ")"）。
 */
public enum TokenType {
    /** 字段名、布尔字面量、比较符关键字、裸字符串。 */
    IDENT,
    /** 整数或小数（如 0301、3.5），text 保留原始文本。 */
    NUMBER,
    /** 双引号字符串，text 为解码后的内容（不含引号）。 */
    STRING,
    /** 括号 ( ) 等操作符，text 为具体符号。 */
    OP,
    /** 左花括号 { （集合开始）。 */
    LBRACE,
    /** 右花括号 } （集合结束）。 */
    RBRACE,
    /** 左方括号 [ （between 区间开始）。 */
    LBRACKET,
    /** 右方括号 ] （between 区间结束）。 */
    RBRACKET,
    /** 分隔符逗号 , 。 */
    SEP,
    /** ISO 日期字面量，如 2026-01-01。 */
    DATE,
    /** 逻辑连接词 and。 */
    AND,
    /** 逻辑连接词 or。 */
    OR,
    /** 输入结束。 */
    EOF
}
