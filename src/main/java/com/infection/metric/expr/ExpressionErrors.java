package com.infection.metric.expr;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 口径表达式统一错误出口（任务27）。
 *
 * <p>所有解析、词法、类型、求值、渲染失败路径一律经此类构造 {@link AppException}，
 * 不向外抛裸 RuntimeException。异常结构含 code / message / position（行:列）/
 * expected / actual / hint / context。位置确实拿不到时保持 null，不伪造。</p>
 *
 * <p>文案渲染 {@link #renderWithContext(String, Map)} 直接复用 {@link ErrorCodes#render}，
 * 同一错误码在不同上下文（口径编码、字段名、数据源名）渲染时带上下文变量。</p>
 */
public final class ExpressionErrors {

    private ExpressionErrors() {}

    /** 复用 ErrorCodes.render，把上下文变量替换进中文文案。 */
    public static String renderWithContext(String code, Map<String, String> ctx) {
        return ErrorCodes.render(code, ctx);
    }

    /** 变长键值对版文案渲染：renderWithContext(code, "field", "dept_code", ...)。 */
    public static String renderWithContext(String code, String... kvPairs) {
        return ErrorCodes.render(code, toMap(kvPairs));
    }

    /**
     * 构造统一异常结构。context 中的 position / expected / actual 会同时写入
     * AppException 的对应字段，使 {@link AppException#render()} 呈现位置与期望/实际。
     */
    public static AppException error(String code, Map<String, String> ctx) {
        Map<String, String> safe = ctx == null
                ? Collections.emptyMap() : new LinkedHashMap<>(ctx);
        String message = ErrorCodes.render(code, safe);
        return new AppException(code, message,
                safe.get("position"),
                safe.get("expected"),
                safe.get("actual"),
                ErrorCodes.hint(code),
                safe, null);
    }

    public static AppException error(String code, String... kvPairs) {
        return error(code, toMap(kvPairs));
    }

    /**
     * 带行列位置的失败。position 同时写入 context（供含 {position} 的文案替换）
     * 与 AppException.position 字段（供 render() 展示）。
     */
    public static AppException errorAt(String code, int line, int col, Map<String, String> ctx) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (ctx != null) merged.putAll(ctx);
        merged.putIfAbsent("position", line + ":" + col);
        return error(code, merged).at(line, col);
    }

    public static AppException errorAt(String code, int line, int col, String... kvPairs) {
        return errorAt(code, line, col, toMap(kvPairs));
    }

    private static Map<String, String> toMap(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }
}
