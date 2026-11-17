package com.infection.metric.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AtomicFiles;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.EnvConfig;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;
import com.infection.metric.common.OutboundTransport;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 口径条目发布写端点：POST /api/v1/indicator-definitions。
 * 幂等键 + 回执落库 + 前置校验（状态 PENDING_REVIEW / 字段对账通过 / 依赖图无环）。
 */
public final class PublishClient {

    public static final String PUBLISH_PATH = "/api/v1/indicator-definitions";

    /** 前置校验闸门：测试可注入内存判定，产品默认由真实状态/对账/依赖图结果驱动。 */
    public interface PreChecks {
        boolean fieldReconcilePassed();
        boolean dependencyGraphAcyclic();
    }

    public record PublishReceipt(String indicatorCode, String definitionVersion, String idempotencyKey,
                                 String endpointReturnedId, String timestamp, List<String> changedEntries,
                                 boolean actuallyChanged) {
        public ObjectNode toJson() {
            ObjectNode o = JsonSupport.object();
            o.put("indicator_code", indicatorCode);
            o.put("definition_version", definitionVersion);
            o.put("idempotency_key", idempotencyKey);
            o.put("endpoint_returned_id", endpointReturnedId);
            o.put("timestamp", timestamp);
            o.put("actually_changed", actuallyChanged);
            ArrayNode arr = o.putArray("changed_entries");
            for (String s : changedEntries) arr.add(s);
            return o;
        }
        public static PublishReceipt fromJson(JsonNode n) {
            return new PublishReceipt(
                    n.path("indicator_code").asText(""), n.path("definition_version").asText(""),
                    n.path("idempotency_key").asText(""), n.path("endpoint_returned_id").asText(""),
                    n.path("timestamp").asText(""), JsonSupport.stringList(n.path("changed_entries")),
                    n.path("actually_changed").asBoolean(false));
        }
    }

    /** 简单回执库：按 indicator_code 反查，同键重复发布不产生第二条记录。 */
    public static final class ReceiptStore {
        private final Path file;
        private final Map<String, PublishReceipt> receipts = new LinkedHashMap<>();
        public ReceiptStore(Path file) {
            this.file = file;
            if (AtomicFiles.exists(file)) {
                JsonNode n = JsonSupport.parse(AtomicFiles.readString(file));
                JsonNode arr = n.path("receipts");
                if (arr.isArray()) for (JsonNode r : arr) {
                    PublishReceipt p = PublishReceipt.fromJson(r);
                    receipts.put(p.indicatorCode() + "|" + p.definitionVersion(), p);
                }
            }
        }
        public boolean contains(String code, String version) { return receipts.containsKey(code + "|" + version); }
        public int publishedVersionCount(String code) {
            return (int) receipts.keySet().stream().filter(k -> k.startsWith(code + "|")).count();
        }
        public PublishReceipt find(String code) {
            return receipts.values().stream().filter(p -> p.indicatorCode().equals(code)).findFirst().orElse(null);
        }
        public void append(PublishReceipt receipt) {
            String key = receipt.indicatorCode() + "|" + receipt.definitionVersion();
            if (receipts.containsKey(key)) return;                   // 幂等：不产生第二条
            receipts.put(key, receipt);
            persist();
        }
        private void persist() {
            ObjectNode root = JsonSupport.object();
            ArrayNode arr = root.putArray("receipts");
            for (PublishReceipt p : receipts.values()) arr.add(p.toJson());
            JsonSupport.writeAtomic(file, root);
        }
        public int size() { return receipts.size(); }
    }

    private final OutboundTransport transport;
    private final String qcBaseUrl;
    private final ReceiptStore receipts;
    private final PreChecks preChecks;

    public PublishClient(OutboundTransport transport, String qcBaseUrl, ReceiptStore receipts, PreChecks preChecks) {
        this.transport = transport;
        this.qcBaseUrl = qcBaseUrl.endsWith("/") ? qcBaseUrl.substring(0, qcBaseUrl.length() - 1) : qcBaseUrl;
        this.receipts = receipts;
        this.preChecks = preChecks;
    }

    public PublishReceipt publish(MetricDefinition definition, List<String> changedEntries) {
        String code = definition.getIndicatorCode();
        String version = definition.getDefinitionVersion();
        // 重复发布：本地已发布版本数仍为 1，不产生第二条
        if (receipts.contains(code, version)) return receipts.find(code);

        List<String> failures = new ArrayList<>();
        if (definition.getStatus() != MetricStatus.PENDING_REVIEW) failures.add("状态不是 PENDING_REVIEW");
        if (!preChecks.fieldReconcilePassed()) failures.add("字段对账未通过");
        if (!preChecks.dependencyGraphAcyclic()) failures.add("依赖图成环");
        if (!failures.isEmpty()) {
            throw ErrorCodes.error(ErrorCodes.E_PUBLISH_001, "reason", String.join("；", failures));
        }

        String key = IdempotencyKeys.publish(code, version);
        OutboundRequest req = OutboundRequest.builder("POST", qcBaseUrl + PUBLISH_PATH)
                .bearer(envToken("QC_METRICS_TOKEN"))
                .idempotencyKey(key)
                .jsonBody(buildBody(definition))
                .build();
        OutboundResponse resp = transport.send(req);
        if (!resp.is2xx()) {
            throw FailureClassifier.error(FailureClassifier.of(resp.getStatusCode(), false, false),
                    req.getUrl(), resp.getStatusCode());
        }
        JsonNode body = JsonSupport.parse(resp.getBody().isBlank() ? "{}" : resp.getBody());
        PublishReceipt receipt = new PublishReceipt(code, version, key,
                body.path("id").asText(body.path("identifier").asText("")),
                java.time.Instant.now().toString(), changedEntries,
                body.path("changed").asBoolean(true));
        receipts.append(receipt);
        return receipt;
    }

    private static String envToken(String key) {
        String t = EnvConfig.fromEnvironment().get(key);
        return t == null ? "" : t;
    }

    static String buildBody(MetricDefinition d) {
        ObjectNode o = JsonSupport.object();
        o.put("indicator_code", d.getIndicatorCode());
        o.put("definition_version", d.getDefinitionVersion());
        o.put("title", d.getTitle());
        if (d.getNumerator() != null) o.set("numerator", d.getNumerator().toJson());
        if (d.getDenominator() != null) o.set("denominator", d.getDenominator().toJson());
        ArrayNode ex = o.putArray("exclusions");
        for (var e : d.getExclusions()) ex.add(e.toJson());
        ArrayNode depts = o.putArray("dept_codes");
        for (String s : d.getDeptCodes()) depts.add(s);
        ArrayNode sources = o.putArray("data_source_fields");
        for (var s : d.getDataSources()) sources.add(s.toJson());
        o.put("effective_from", d.getEffectiveFrom());
        o.put("effective_to", d.getEffectiveTo());
        o.put("status", d.getStatus().name());
        return JsonSupport.pretty(o);
    }

    public ReceiptStore receipts() { return receipts; }
}
