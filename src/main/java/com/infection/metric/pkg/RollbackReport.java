package com.infection.metric.pkg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * 合并事务回滚报告：让「合并到一半失败」这件事可观测。
 *
 * <p>记录失败步骤、回滚条目数（被撤销的已写入条目）、回滚后版本
 * （合并前本地库生效的版本集合签名）、回滚时间，以及写入运行摘要与
 * 审计表的本次回滚记录文本。</p>
 */
public final class RollbackReport {

    private final String packageVersion;
    private final String targetVersion;
    private final String failedStep;
    private final int rolledBackEntryCount;
    private final String versionAfterRollback;
    private final String rolledBackAt;
    private final List<String> auditRecords;
    private final String runSummary;

    private RollbackReport(Builder b) {
        this.packageVersion = b.packageVersion;
        this.targetVersion = b.targetVersion;
        this.failedStep = b.failedStep;
        this.rolledBackEntryCount = b.rolledBackEntryCount;
        this.versionAfterRollback = b.versionAfterRollback;
        this.rolledBackAt = b.rolledBackAt;
        this.auditRecords = List.copyOf(b.auditRecords);
        this.runSummary = b.runSummary;
    }

    public static Builder builder() { return new Builder(); }

    public String getPackageVersion() { return packageVersion; }
    public String getTargetVersion() { return targetVersion; }
    public String getFailedStep() { return failedStep; }
    public int getRolledBackEntryCount() { return rolledBackEntryCount; }
    public String getVersionAfterRollback() { return versionAfterRollback; }
    public String getRolledBackAt() { return rolledBackAt; }
    public List<String> getAuditRecords() { return auditRecords; }
    public String getRunSummary() { return runSummary; }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("package_version", packageVersion);
        o.put("target_version", targetVersion);
        o.put("failed_step", failedStep);
        o.put("rolled_back_entry_count", rolledBackEntryCount);
        o.put("version_after_rollback", versionAfterRollback);
        o.put("rolled_back_at", rolledBackAt);
        o.put("run_summary", runSummary);
        ArrayNode arr = o.putArray("audit_records");
        for (String a : auditRecords) arr.add(a);
        return o;
    }

    public static RollbackReport fromJson(JsonNode n) {
        Builder b = builder()
                .packageVersion(n.path("package_version").asText(""))
                .targetVersion(n.path("target_version").asText(""))
                .failedStep(n.path("failed_step").asText(""))
                .rolledBackEntryCount(n.path("rolled_back_entry_count").asInt(0))
                .versionAfterRollback(n.path("version_after_rollback").asText(""))
                .rolledBackAt(n.path("rolled_back_at").asText(""))
                .runSummary(n.path("run_summary").asText(""));
        JsonNode arr = n.path("audit_records");
        if (arr.isArray()) for (JsonNode a : arr) b.addAuditRecord(a.asText());
        return b.build();
    }

    public static final class Builder {
        private String packageVersion = "", targetVersion = "", failedStep = "", versionAfterRollback = "";
        private int rolledBackEntryCount = 0;
        private String rolledBackAt = "", runSummary = "";
        private final List<String> auditRecords = new ArrayList<>();

        public Builder packageVersion(String v) { this.packageVersion = v; return this; }
        public Builder targetVersion(String v) { this.targetVersion = v; return this; }
        public Builder failedStep(String v) { this.failedStep = v; return this; }
        public Builder rolledBackEntryCount(int v) { this.rolledBackEntryCount = v; return this; }
        public Builder versionAfterRollback(String v) { this.versionAfterRollback = v; return this; }
        public Builder rolledBackAt(String v) { this.rolledBackAt = v; return this; }
        public Builder addAuditRecord(String v) { this.auditRecords.add(v); return this; }
        public Builder runSummary(String v) { this.runSummary = v; return this; }
        public RollbackReport build() { return new RollbackReport(this); }
    }
}
