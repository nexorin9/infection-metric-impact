package com.infection.metric.expr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 条件树：口径表达式的唯一表示。
 * 分支 {op:AND|OR, children:[...]}；叶子 {field, cmp, value}。
 * value ∈ string | number | boolean | [lo,hi]（between）| [str]（in/not_in）。
 */
public final class ConditionTree {

    public static final Set<String> OPS = Set.of("AND", "OR");
    public static final Set<String> CMPS = Set.of(
            "eq", "ne", "in", "not_in", "between", "gte", "lte", "gt", "lt", "contains");

    private final String op;                 // 分支节点为 AND/OR，叶子为 null
    private final List<ConditionTree> children;
    private final String field;              // 叶子字段
    private final String cmp;                // 叶子比较符
    private final Object value;              // 叶子取值：String/Number/Boolean/List

    private ConditionTree(String op, List<ConditionTree> children,
                          String field, String cmp, Object value) {
        this.op = op;
        this.children = children == null ? List.of() : List.copyOf(children);
        this.field = field;
        this.cmp = cmp;
        this.value = value;
    }

    public static ConditionTree leaf(String field, String cmp, Object value) {
        if (field == null || field.isBlank()) {
            throw ErrorCodes.error(ErrorCodes.E_DATA_001, "field", "(空字段名)");
        }
        if (!CMPS.contains(cmp)) {
            throw ErrorCodes.error(ErrorCodes.E_PARSE_002, "cmp", String.valueOf(cmp));
        }
        return new ConditionTree(null, List.of(), field, cmp, normalizeValue(value));
    }

    public static ConditionTree branch(String op, List<ConditionTree> children) {
        String upper = op == null ? "" : op.toUpperCase();
        if (!OPS.contains(upper)) {
            throw new AppException("E_PARSE_003", "条件树分支 op 只能是 AND 或 OR，实际 " + op);
        }
        return new ConditionTree(upper, children == null ? List.of() : children, null, null, null);
    }

    public static ConditionTree and(ConditionTree... children) {
        return branch("AND", List.of(children));
    }

    public static ConditionTree or(ConditionTree... children) {
        return branch("OR", List.of(children));
    }

    private static Object normalizeValue(Object v) {
        if (v instanceof JsonNode jn) return jsonToJava(jn);
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) out.add(normalizeValue(o));
            return out;
        }
        return v;
    }

    static Object jsonToJava(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonNode e : n) out.add(jsonToJava(e));
            return out;
        }
        if (n.isBoolean()) return n.booleanValue();
        if (n.isNumber()) return n.numberValue();
        if (n.isTextual()) return n.textValue();
        return n.toString();
    }

    static JsonNode javaToJson(Object v) {
        return JsonSupport.mapper().valueToTree(v);
    }

    public boolean isLeaf() { return op == null; }
    public String getOp() { return op; }
    public List<ConditionTree> getChildren() { return children; }
    public String getField() { return field; }
    public String getCmp() { return cmp; }
    public Object getValue() { return value; }

    /** 全部叶子（深度优先）。 */
    public List<ConditionTree> leaves() {
        List<ConditionTree> out = new ArrayList<>();
        collectLeaves(this, out);
        return out;
    }

    private static void collectLeaves(ConditionTree t, List<ConditionTree> out) {
        if (t.isLeaf()) { out.add(t); return; }
        for (ConditionTree c : t.children) collectLeaves(c, out);
    }

    /** 口径声明依赖的字段名清单（去重、保持出现顺序）。 */
    public Set<String> declaredFields() {
        Set<String> out = new LinkedHashSet<>();
        for (ConditionTree leaf : leaves()) out.add(leaf.field);
        return out;
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        if (isLeaf()) {
            o.put("field", field);
            o.put("cmp", cmp);
            o.set("value", javaToJson(value));
        } else {
            o.put("op", op);
            ArrayNode arr = o.putArray("children");
            for (ConditionTree c : children) arr.add(c.toJson());
        }
        return o;
    }

    public static ConditionTree fromJson(JsonNode n) {
        if (n == null || n.isNull()) {
            throw new AppException("E_PARSE_003", "条件树节点为空");
        }
        if (!n.isObject()) {
            throw new AppException("E_PARSE_003", "条件树节点必须是对象");
        }
        if (n.has("op") || n.has("children")) {
            List<ConditionTree> kids = new ArrayList<>();
            JsonNode arr = n.path("children");
            if (arr.isArray()) for (JsonNode k : arr) kids.add(fromJson(k));
            return branch(n.path("op").asText(""), kids);
        }
        String f = n.path("field").asText("");
        String c = n.path("cmp").asText("");
        Object v = jsonToJava(n.path("value"));
        return leaf(f, c, v);
    }

    public static ConditionTree fromJson(String text) { return fromJson(JsonSupport.parse(text)); }

    /** 规范化后的 canonical form JSON 串（交换律、集合去重排序、between 定序）。 */
    public String canonicalJson() { return Canonicalizer.canonicalJson(this); }

    /** 口径指纹：canonical form 的 sha256。 */
    public String fingerprint() { return Canonicalizer.fingerprint(this); }

    public static boolean isNumericValue(Object v) {
        if (v instanceof Number) return true;
        if (v instanceof String s) {
            try { new BigDecimal(s); return true; } catch (NumberFormatException e) { return false; }
        }
        return false;
    }

    @Override public boolean equals(Object o) {
        return o instanceof ConditionTree t && canonicalJson().equals(t.canonicalJson());
    }
    @Override public int hashCode() { return Objects.hashCode(canonicalJson()); }
    @Override public String toString() { return toJson().toString(); }
}
