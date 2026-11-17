package com.infection.metric.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.FieldDef;
import com.infection.metric.common.FieldDictionary;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 字段字典对账器：缺失 / 多余 / 类型不符三类差异逐条列出；缺失或类型不符拦截发布。 */
public final class FieldReconciler {

    public enum DifferenceType { MISSING, EXTRA, TYPE_MISMATCH }

    public record Difference(DifferenceType type, String field,
                             String expectedType, String actualType, String message) {
        public boolean blocksPublish() {
            return type == DifferenceType.MISSING || type == DifferenceType.TYPE_MISMATCH;
        }
        public ObjectNode toJson() {
            ObjectNode o = JsonSupport.object();
            o.put("type", type.name());
            o.put("field", field);
            o.put("expected_type", expectedType == null ? "" : expectedType);
            o.put("actual_type", actualType == null ? "" : actualType);
            o.put("message", message);
            o.put("blocks_publish", blocksPublish());
            return o;
        }
    }

    public static final class ReconcileReport {
        private final List<Difference> differences;
        public ReconcileReport(List<Difference> differences) { this.differences = List.copyOf(differences); }
        public List<Difference> getDifferences() { return differences; }
        public List<Difference> blocking() {
            return differences.stream().filter(Difference::blocksPublish).toList();
        }
        public boolean hasMissing() { return differences.stream().anyMatch(d -> d.type == DifferenceType.MISSING); }
        public boolean hasTypeMismatch() { return differences.stream().anyMatch(d -> d.type == DifferenceType.TYPE_MISMATCH); }
        public boolean hasExtra() { return differences.stream().anyMatch(d -> d.type == DifferenceType.EXTRA); }
        public boolean isPublishAllowed() { return blocking().isEmpty(); }

        public ObjectNode toJson() {
            ObjectNode o = JsonSupport.object();
            o.put("publish_allowed", isPublishAllowed());
            o.put("difference_count", differences.size());
            o.put("blocking_count", blocking().size());
            ArrayNode arr = o.putArray("differences");
            for (Difference d : differences) arr.add(d.toJson());
            return o;
        }

        /** 中文人话条目，供信息科接口工程师直接拿去改接口。 */
        public List<String> humanLines() {
            List<String> lines = new ArrayList<>();
            for (Difference d : differences) lines.add(d.message());
            if (lines.isEmpty()) lines.add("字段对账通过：口径声明字段与数据源字典完全一致。");
            return lines;
        }
    }

    /**
     * @param declared 口径声明的字段名
     * @param expectedTypes 口径声明的字段类型（可为空表示不校验类型）
     * @param dictionary 数据源字段字典
     */
    public ReconcileReport reconcile(List<String> declared, java.util.Map<String, String> expectedTypes,
                                      FieldDictionary dictionary) {
        List<Difference> diffs = new ArrayList<>();
        Set<String> declaredSet = new LinkedHashSet<>(declared);
        for (String field : declaredSet) {
            if (!dictionary.has(field)) {
                diffs.add(new Difference(DifferenceType.MISSING, field,
                        expectedTypes.getOrDefault(field, ""), "",
                        "缺失：口径声明了字段 " + field + "，数据源字典未提供该字段"));
                continue;
            }
            String expected = expectedTypes.get(field);
            String actual = dictionary.typeOf(field);
            if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(actual)) {
                diffs.add(new Difference(DifferenceType.TYPE_MISMATCH, field, expected, actual,
                        "类型不符：字段 " + field + " 口径声明 " + expected + "，数据源实际 " + actual));
            }
        }
        for (FieldDef def : dictionary.all()) {
            if (!declaredSet.contains(def.getName())) {
                diffs.add(new Difference(DifferenceType.EXTRA, def.getName(), "", def.getType(),
                        "多余：数据源字典有字段 " + def.getName() + "，口径未声明（仅提示，不拦截）"));
            }
        }
        return new ReconcileReport(diffs);
    }

    /** 发布前拦截：存在缺失或类型不符时拒绝并给出逐条原因。 */
    public void requirePublishable(ReconcileReport report) {
        if (!report.isPublishAllowed()) {
            List<String> reasons = report.blocking().stream().map(Difference::message).toList();
            throw ErrorCodes.error(ErrorCodes.E_PUBLISH_001,
                    "reason", String.join("；", reasons));
        }
    }

    /** 从 GET /api/v1/metadata/fields 响应构造字典。 */
    public static FieldDictionary dictionaryFrom(JsonNode response) {
        JsonNode fields = response.path("fields");
        List<FieldDef> defs = new ArrayList<>();
        if (fields.isArray()) {
            for (JsonNode f : fields) {
                defs.add(new FieldDef(
                        f.path("field").asText(f.path("name").asText("")),
                        f.path("type").asText("string"),
                        f.path("entity").asText(""),
                        f.path("required").asBoolean(false),
                        f.path("source").asText("EMR")));
            }
        }
        return new FieldDictionary(response.path("version").asText("v1"), defs);
    }
}
