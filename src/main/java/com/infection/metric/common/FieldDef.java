package com.infection.metric.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/** 字段字典条目：与 GET /api/v1/metadata/fields 同一结构。 */
public final class FieldDef {
    private final String name;
    private final String type;
    private final String entity;
    private final boolean required;
    private final String source;

    public FieldDef(String name, String type, String entity, boolean required, String source) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = type == null ? "string" : type;
        this.entity = entity == null ? "" : entity;
        this.required = required;
        this.source = source == null ? "" : source;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getEntity() { return entity; }
    public boolean isRequired() { return required; }
    public String getSource() { return source; }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("field", name);
        o.put("type", type);
        o.put("entity", entity);
        o.put("required", required);
        o.put("source", source);
        return o;
    }

    public static FieldDef fromJson(JsonNode n) {
        return new FieldDef(n.path("field").asText(n.path("name").asText("")),
                n.path("type").asText("string"), n.path("entity").asText(""),
                n.path("required").asBoolean(false), n.path("source").asText(""));
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof FieldDef f)) return false;
        return name.equals(f.name) && type.equals(f.type) && entity.equals(f.entity);
    }
    @Override public int hashCode() { return Objects.hash(name, type, entity); }
    @Override public String toString() { return name + ":" + type + "@" + source; }
}
