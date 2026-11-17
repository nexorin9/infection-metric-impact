package com.infection.metric.pkg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 口径包合并回执：合并成功后落库、可按包版本或幂等键反查。
 *
 * <p>字段含时间戳、变更条目（本次已确认并入的条目编号）、逐条确认人、
 * 是否真有变更、幂等键与合并端点返回标识。</p>
 */
public final class MergeReceipt {

    private final String packageVersion;
    private final String targetVersion;
    private final String mergedAt;
    private final List<String> changedEntries;
    private final Map<String, String> confirmers;
    private final boolean changed;
    private final String idempotencyKey;
    private final String endpointReturnId;

    private MergeReceipt(Builder b) {
        this.packageVersion = b.packageVersion;
        this.targetVersion = b.targetVersion;
        this.mergedAt = b.mergedAt;
        this.changedEntries = List.copyOf(b.changedEntries);
        this.confirmers = new LinkedHashMap<>(b.confirmers);
        this.changed = b.changed;
        this.idempotencyKey = b.idempotencyKey;
        this.endpointReturnId = b.endpointReturnId;
    }

    public static Builder builder() { return new Builder(); }

    public String getPackageVersion() { return packageVersion; }
    public String getTargetVersion() { return targetVersion; }
    public String getMergedAt() { return mergedAt; }
    public List<String> getChangedEntries() { return changedEntries; }
    public Map<String, String> getConfirmers() { return java.util.Collections.unmodifiableMap(confirmers); }
    public boolean isChanged() { return changed; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getEndpointReturnId() { return endpointReturnId; }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("package_version", packageVersion);
        o.put("target_version", targetVersion);
        o.put("merged_at", mergedAt);
        o.put("changed", changed);
        o.put("idempotency_key", idempotencyKey);
        o.put("endpoint_return_id", endpointReturnId);
        ArrayNode entries = o.putArray("changed_entries");
        for (String e : changedEntries) entries.add(e);
        ObjectNode conf = o.putObject("confirmers");
        for (Map.Entry<String, String> e : confirmers.entrySet()) conf.put(e.getKey(), e.getValue());
        return o;
    }

    public static MergeReceipt fromJson(JsonNode n) {
        Builder b = builder()
                .packageVersion(n.path("package_version").asText(""))
                .targetVersion(n.path("target_version").asText(""))
                .mergedAt(n.path("merged_at").asText(""))
                .changed(n.path("changed").asBoolean(false))
                .idempotencyKey(n.path("idempotency_key").asText(""))
                .endpointReturnId(n.path("endpoint_return_id").asText(""));
        JsonNode arr = n.path("changed_entries");
        if (arr.isArray()) for (JsonNode e : arr) b.addChangedEntry(e.asText());
        JsonNode conf = n.path("confirmers");
        if (conf.isObject()) conf.fields().forEachRemaining(e -> b.confirmer(e.getKey(), e.getValue().asText()));
        return b.build();
    }

    public static final class Builder {
        private String packageVersion = "", targetVersion = "", mergedAt = "";
        private final List<String> changedEntries = new ArrayList<>();
        private final Map<String, String> confirmers = new LinkedHashMap<>();
        private boolean changed = true;
        private String idempotencyKey = "", endpointReturnId = "";

        public Builder packageVersion(String v) { this.packageVersion = v; return this; }
        public Builder targetVersion(String v) { this.targetVersion = v; return this; }
        public Builder mergedAt(String v) { this.mergedAt = v; return this; }
        public Builder addChangedEntry(String v) { this.changedEntries.add(v); return this; }
        public Builder changedEntries(List<String> v) { this.changedEntries.clear(); this.changedEntries.addAll(v); return this; }
        public Builder confirmer(String entryNo, String reviewer) { this.confirmers.put(entryNo, reviewer); return this; }
        public Builder confirmers(Map<String, String> v) { this.confirmers.clear(); this.confirmers.putAll(v); return this; }
        public Builder changed(boolean v) { this.changed = v; return this; }
        public Builder idempotencyKey(String v) { this.idempotencyKey = v; return this; }
        public Builder endpointReturnId(String v) { this.endpointReturnId = v; return this; }
        public MergeReceipt build() { return new MergeReceipt(this); }
    }
}
