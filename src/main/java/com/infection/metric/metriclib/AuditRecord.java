package com.infection.metric.metriclib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条审计记录：谁在什么时候对哪个口径版本做了什么动作。
 * field_diff 只保存真正变化的字段（field / before / after）；审批动作另带审批人与意见。
 * audit_id 由 AuditTrail 按审计内容哈希生成，作为幂等键，不随时间变化。
 */
public final class AuditRecord {

    private final String auditId;
    private final String indicatorCode;
    private final String definitionVersion;
    private final String actor;
    private final String action;
    private final String timestamp;
    private final List<FieldDiff> fieldDiff;
    private final String approver;
    private final String approvalNote;

    public AuditRecord(String auditId, String indicatorCode, String definitionVersion,
                       String actor, String action, String timestamp,
                       List<FieldDiff> fieldDiff, String approver, String approvalNote) {
        this.auditId = auditId == null ? "" : auditId;
        this.indicatorCode = indicatorCode == null ? "" : indicatorCode;
        this.definitionVersion = definitionVersion == null ? "" : definitionVersion;
        this.actor = actor == null ? "" : actor;
        this.action = action == null ? "" : action;
        this.timestamp = timestamp == null ? "" : timestamp;
        this.fieldDiff = fieldDiff == null ? List.of() : List.copyOf(fieldDiff);
        this.approver = approver == null ? "" : approver;
        this.approvalNote = approvalNote == null ? "" : approvalNote;
    }

    public String getAuditId() { return auditId; }
    public String getIndicatorCode() { return indicatorCode; }
    public String getDefinitionVersion() { return definitionVersion; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getTimestamp() { return timestamp; }
    public List<FieldDiff> getFieldDiff() { return fieldDiff; }
    public String getApprover() { return approver; }
    public String getApprovalNote() { return approvalNote; }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("audit_id", auditId);
        o.put("indicator_code", indicatorCode);
        o.put("definition_version", definitionVersion);
        o.put("actor", actor);
        o.put("action", action);
        o.put("timestamp", timestamp);
        ArrayNode arr = o.putArray("field_diff");
        for (FieldDiff d : fieldDiff) arr.add(d.toJson());
        o.put("approver", approver);
        o.put("approval_note", approvalNote);
        return o;
    }

    public static AuditRecord fromJson(JsonNode n) {
        List<FieldDiff> diffs = new ArrayList<>();
        JsonNode arr = n.path("field_diff");
        if (arr.isArray()) {
            for (JsonNode d : arr) diffs.add(FieldDiff.fromJson(d));
        }
        return new AuditRecord(
                n.path("audit_id").asText(""),
                n.path("indicator_code").asText(""),
                n.path("definition_version").asText(""),
                n.path("actor").asText(""),
                n.path("action").asText(""),
                n.path("timestamp").asText(""),
                diffs,
                n.path("approver").asText(""),
                n.path("approval_note").asText(""));
    }

    /** 字段级差异：{field, before, after}。 */
    public static final class FieldDiff {
        private final String field;
        private final String before;
        private final String after;

        public FieldDiff(String field, String before, String after) {
            this.field = field == null ? "" : field;
            this.before = before == null ? "" : before;
            this.after = after == null ? "" : after;
        }

        public String getField() { return field; }
        public String getBefore() { return before; }
        public String getAfter() { return after; }

        public ObjectNode toJson() {
            ObjectNode o = JsonSupport.object();
            o.put("field", field);
            o.put("before", before);
            o.put("after", after);
            return o;
        }

        public static FieldDiff fromJson(JsonNode n) {
            return new FieldDiff(n.path("field").asText(""),
                    n.path("before").asText(""), n.path("after").asText(""));
        }
    }
}
