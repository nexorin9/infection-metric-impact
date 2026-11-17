package com.infection.metric.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 字段字典：按 source + entity 提供字段类型，带版本快照。 */
public final class FieldDictionary {

    private final String version;
    private final Map<String, FieldDef> fields;

    public FieldDictionary(String version, List<FieldDef> defs) {
        this.version = version == null || version.isBlank() ? "v1" : version;
        Map<String, FieldDef> m = new LinkedHashMap<>();
        for (FieldDef d : defs) m.put(d.getName(), d);
        this.fields = Collections.unmodifiableMap(m);
    }

    public static FieldDictionary fromJson(JsonNode node) {
        List<FieldDef> defs = new ArrayList<>();
        JsonNode arr = node.path("fields");
        if (arr.isArray()) for (JsonNode n : arr) defs.add(FieldDef.fromJson(n));
        return new FieldDictionary(node.path("version").asText("v1"), defs);
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("version", version);
        ArrayNode arr = o.putArray("fields");
        for (FieldDef d : fields.values()) arr.add(d.toJson());
        return o;
    }

    public String version() { return version; }
    public boolean has(String field) { return fields.containsKey(field); }
    public FieldDef get(String field) { return fields.get(field); }
    public String typeOf(String field) {
        FieldDef d = fields.get(field);
        return d == null ? null : d.getType();
    }
    public List<FieldDef> all() { return new ArrayList<>(fields.values()); }
    public int size() { return fields.size(); }

    /** 检查口径声明的字段是否都在字典里；缺失抛 E_FIELD_001，文案带 code/field/source。 */
    public void requireFields(List<String> declared, String indicatorCode, String source) {
        for (String f : declared) {
            if (!has(f)) {
                throw ErrorCodes.error(ErrorCodes.E_FIELD_001,
                        "code", indicatorCode, "field", f, "source", source);
            }
        }
    }
}
