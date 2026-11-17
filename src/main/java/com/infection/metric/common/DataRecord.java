package com.infection.metric.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 内存记录模型：患者明细只在内存参与聚合，落盘仅聚合值 + 口径元数据 + 差异说明。
 * 字段缺失与脏值必须显式处理，不得静默按 0 计入分母。
 */
public final class DataRecord {
    private final Map<String, Object> values;

    public DataRecord(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static Builder builder() { return new Builder(); }

    public boolean has(String field) { return values.containsKey(field); }
    public Set<String> fields() { return values.keySet(); }

    public Object get(String field) {
        if (!values.containsKey(field)) {
            throw ErrorCodes.error(ErrorCodes.E_DATA_001, "field", field);
        }
        return values.get(field);
    }

    public Object getOrNull(String field) { return values.get(field); }

    public boolean isBlank(String field) {
        Object v = values.get(field);
        return v == null || (v instanceof String s && s.isBlank());
    }

    public String asText(String field) {
        Object v = get(field);
        return v == null ? "" : String.valueOf(v);
    }

    public Map<String, Object> asMap() { return values; }

    public static final class Builder {
        private final Map<String, Object> m = new LinkedHashMap<>();
        public Builder put(String k, Object v) { m.put(k, v); return this; }
        public DataRecord build() { return new DataRecord(m); }
    }
}
