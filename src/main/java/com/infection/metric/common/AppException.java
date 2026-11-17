package com.infection.metric.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一失败语义。所有失败路径都抛这个结构，不用裸 RuntimeException。
 * 字段：code / message / position（行:列）/ expected / actual / hint / context。
 * 位置确实拿不到时保持 null，不伪造。
 */
public class AppException extends RuntimeException {

    private final String code;
    private final String position;
    private final String expected;
    private final String actual;
    private final String hint;
    private final Map<String, String> context;

    public AppException(String code, String message) {
        this(code, message, null, null, null, null, Collections.emptyMap(), null);
    }

    public AppException(String code, String message, Throwable cause) {
        this(code, message, null, null, null, null, Collections.emptyMap(), cause);
    }

    public AppException(String code, String message, String position, String expected,
                        String actual, String hint, Map<String, String> context, Throwable cause) {
        super(message, cause);
        this.code = code == null ? "E_UNKNOWN" : code;
        this.position = position;
        this.expected = expected;
        this.actual = actual;
        this.hint = hint;
        this.context = context == null ? Collections.emptyMap() : new LinkedHashMap<>(context);
    }

    public static AppException of(String code, String message) {
        return new AppException(code, message);
    }

    public AppException at(int line, int col) {
        return new AppException(code, getMessage(), line + ":" + col,
                expected, actual, hint, context, getCause());
    }

    public AppException at(String position) {
        return new AppException(code, getMessage(), position,
                expected, actual, hint, context, getCause());
    }

    public AppException expecting(String expectedValue, String actualValue) {
        return new AppException(code, getMessage(), position,
                expectedValue, actualValue, hint, context, getCause());
    }

    public AppException with(String key, String value) {
        Map<String, String> merged = new LinkedHashMap<>(context);
        merged.put(key, value);
        return new AppException(code, getMessage(), position,
                expected, actual, hint, merged, getCause());
    }

    public AppException hinting(String hintText) {
        return new AppException(code, getMessage(), position,
                expected, actual, hintText, context, getCause());
    }

    public String getCode() { return code; }
    public String getPosition() { return position; }
    public String getExpected() { return expected; }
    public String getActual() { return actual; }
    public String getHint() { return hint; }
    public Map<String, String> getContext() { return Collections.unmodifiableMap(context); }

    /** 渲染成业务可读的一句文案，供 UI 与运行摘要直出。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(code).append("] ").append(getMessage());
        if (position != null && !position.isBlank()) {
            sb.append("（位置 ").append(position).append('）');
        }
        if (!context.isEmpty()) {
            sb.append("；上下文：");
            boolean first = true;
            for (Map.Entry<String, String> e : context.entrySet()) {
                if (!first) sb.append('，');
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
        }
        if (expected != null) sb.append("；期望 ").append(expected);
        if (actual != null) sb.append("，实际 ").append(actual);
        if (hint != null && !hint.isBlank()) sb.append("；提示：").append(hint);
        return sb.toString();
    }
}
