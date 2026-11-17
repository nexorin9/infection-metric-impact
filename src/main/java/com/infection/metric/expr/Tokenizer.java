package com.infection.metric.expr;

import com.infection.metric.common.ErrorCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 口径表达式词法层（任务28）：把文本切成带起始位置（行:列）的 token。
 *
 * <p>产出 IDENT / NUMBER / STRING / OP / LBRACE / RBRACE / LBRACKET / RBRACKET /
 * SEP / DATE / AND / OR / EOF。支持双引号与反斜杠转义、字面反斜杠、ISO 日期、
 * 整数与小数；空白与 # / // 行注释跳过；未闭合字符串在 EOF 抛 E_LEX_001 并指出起始位置；
 * 非法字符抛 E_LEX_002 带位置与实际字符。</p>
 *
 * <p>括号 ( ) 归入 OP（text 即 "(" 或 ")"）；逗号归入 SEP；
 * [ ] 归入 LBRACKET/RBRACKET；{ } 归入 LBRACE/RBRACE；
 * and/or 关键字（大小写不敏感）归入 AND/OR。</p>
 */
public final class Tokenizer {

    /** 仅在当前位置起匹配 YYYY-MM-DD，且其后不紧跟数字，避免把长数字误判为日期。 */
    private static final Pattern DATE_AT = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(?!\\d)");

    private final String src;
    private int idx = 0;
    private int line = 1;
    private int col = 1;

    private Tokenizer(String src) {
        this.src = src == null ? "" : src;
    }

    /** 词法分析的唯一入口：返回含末尾 EOF 的 token 列表。 */
    public static List<Token> tokenize(String text) {
        return new Tokenizer(text).scan();
    }

    private List<Token> scan() {
        List<Token> out = new ArrayList<>();
        while (idx < src.length()) {
            char c = src.charAt(idx);

            if (Character.isWhitespace(c)) {
                advance();
                continue;
            }
            if (c == '#' || (c == '/' && idx + 1 < src.length() && src.charAt(idx + 1) == '/')) {
                skipLineComment();
                continue;
            }

            int startLine = line;
            int startCol = col;

            switch (c) {
                case '(':
                case ')':
                    out.add(new Token(TokenType.OP, String.valueOf(c), startLine, startCol));
                    advance();
                    continue;
                case '[':
                    out.add(new Token(TokenType.LBRACKET, "[", startLine, startCol));
                    advance();
                    continue;
                case ']':
                    out.add(new Token(TokenType.RBRACKET, "]", startLine, startCol));
                    advance();
                    continue;
                case '{':
                    out.add(new Token(TokenType.LBRACE, "{", startLine, startCol));
                    advance();
                    continue;
                case '}':
                    out.add(new Token(TokenType.RBRACE, "}", startLine, startCol));
                    advance();
                    continue;
                case ',':
                    out.add(new Token(TokenType.SEP, ",", startLine, startCol));
                    advance();
                    continue;
                default:
                    break;
            }

            if (c == '"') {
                out.add(readString(startLine, startCol));
                continue;
            }
            if (Character.isDigit(c)) {
                out.add(readDateOrNumber(startLine, startCol));
                continue;
            }
            if (isIdentStart(c)) {
                out.add(readIdent(startLine, startCol));
                continue;
            }

            throw ExpressionErrors.errorAt(ErrorCodes.E_LEX_002, startLine, startCol,
                    "char", String.valueOf(c));
        }
        out.add(new Token(TokenType.EOF, "", line, col));
        return out;
    }

    /** 从当前双引号处读取字符串，解码转义；未闭合到 EOF 抛 E_LEX_001。 */
    private Token readString(int startLine, int startCol) {
        advance(); // 跳过开引号
        StringBuilder sb = new StringBuilder();
        while (idx < src.length()) {
            char c = src.charAt(idx);
            if (c == '"') {
                advance();
                return new Token(TokenType.STRING, sb.toString(), startLine, startCol);
            }
            if (c == '\\') {
                advance(); // 跳过反斜杠
                if (idx >= src.length()) {
                    break; // 转义符后直接 EOF，按未闭合处理
                }
                char e = src.charAt(idx);
                switch (e) {
                    case '"':  sb.append('"');  advance(); break;
                    case '\\': sb.append('\\'); advance(); break;
                    case 'n':  sb.append('\n'); advance(); break;
                    case 't':  sb.append('\t'); advance(); break;
                    case 'r':  sb.append('\r'); advance(); break;
                    default:
                        // 字面反斜杠：保留反斜杠与其后字符（如 Windows 路径 C:\temp）
                        sb.append('\\').append(e);
                        advance();
                        break;
                }
                continue;
            }
            sb.append(c);
            advance();
        }
        throw ExpressionErrors.errorAt(ErrorCodes.E_LEX_001, startLine, startCol);
    }

    /** 优先识别 ISO 日期，否则识别整数/小数；text 保留原始文本（含前导零）。 */
    private Token readDateOrNumber(int startLine, int startCol) {
        Matcher m = DATE_AT.matcher(src.substring(idx));
        if (m.find()) {
            String date = m.group(1);
            advanceBy(date.length());
            return new Token(TokenType.DATE, date, startLine, startCol);
        }
        StringBuilder sb = new StringBuilder();
        while (idx < src.length() && Character.isDigit(src.charAt(idx))) {
            sb.append(src.charAt(idx));
            advance();
        }
        if (idx + 1 < src.length() && src.charAt(idx) == '.'
                && Character.isDigit(src.charAt(idx + 1))) {
            sb.append('.');
            advance();
            while (idx < src.length() && Character.isDigit(src.charAt(idx))) {
                sb.append(src.charAt(idx));
                advance();
            }
        }
        return new Token(TokenType.NUMBER, sb.toString(), startLine, startCol);
    }

    private Token readIdent(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        while (idx < src.length() && isIdentPart(src.charAt(idx))) {
            sb.append(src.charAt(idx));
            advance();
        }
        String word = sb.toString();
        String lower = word.toLowerCase();
        TokenType type;
        if ("and".equals(lower)) {
            type = TokenType.AND;
        } else if ("or".equals(lower)) {
            type = TokenType.OR;
        } else {
            type = TokenType.IDENT;
        }
        return new Token(type, word, startLine, startCol);
    }

    private void skipLineComment() {
        while (idx < src.length() && src.charAt(idx) != '\n') {
            advance();
        }
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '/';
    }

    private void advance() {
        char c = src.charAt(idx);
        idx++;
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
    }

    private void advanceBy(int n) {
        for (int i = 0; i < n && idx < src.length(); i++) {
            advance();
        }
    }
}
