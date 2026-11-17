package com.infection.metric.pkg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.EnvConfig;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;
import com.infection.metric.common.OutboundTransport;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 口径包合并回写：确认完才合并，合并是真实写入。
 *
 * <p>经 {@link OutboundTransport} 注入的合并端点写入
 * {@code POST {qc_metrics_base_url}/api/v1/indicator-packages/{package_version}/merge}，
 * 请求体含已确认条目、逐条确认人、合并后版本号，幂等键
 * {@code Hashes.sha256Of(packageVersion, targetVersion)}。</p>
 *
 * <p>重复导入保护：已合并记录 + 幂等键双保险，同一包重复导入不重复合并，
 * 提示「该包已于 X 合并至版本 Y」并抛 {@link ErrorCodes#E_PKG_003}。
 * 合并回执落库可反查；合并结果本地落库为本院口径新版本（DRAFT 待复核，记来源包版本）。</p>
 *
 * <p>跨模块纪律：不硬依赖 M5 正在写的客户端，出站一律走 common.OutboundTransport
 * 接口（契约测试用内存替身）；令牌只从环境变量 / 构造注入，不写进日志。</p>
 */
public final class PackageMerger {

    private final OutboundTransport transport;
    private final String qcMetricsBaseUrl;
    private final MergeLedger ledger;
    private final String token;

    public PackageMerger(OutboundTransport transport, String qcMetricsBaseUrl, MergeLedger ledger) {
        this(transport, qcMetricsBaseUrl, ledger,
                EnvConfig.fromEnvironment().getOrDefault("QC_METRICS_TOKEN", ""));
    }

    public PackageMerger(OutboundTransport transport, String qcMetricsBaseUrl,
                         MergeLedger ledger, String token) {
        if (transport == null) throw new AppException("E_OUTBOUND_001", "合并端点 OutboundTransport 未注入");
        this.transport = transport;
        this.qcMetricsBaseUrl = qcMetricsBaseUrl == null ? "" : trimTrailingSlash(qcMetricsBaseUrl);
        this.ledger = ledger;
        this.token = token == null ? "" : token;
    }

    /**
     * 执行一次合并。重复检查优先于一切前置，保证「同一包重复导入」稳定命中 E_PKG_003，
     * 不会因为 review 已进入终态而误报其它错误。
     */
    public MergeReceipt merge(PackageReview review, String packageVersion, String targetVersion) {
        String idemKey = Hashes.sha256Of(packageVersion, targetVersion);

        // 双保险：已合并记录 + 幂等键，任一命中即拒绝重复导入
        if (ledger.hasMerged(packageVersion, targetVersion) || ledger.hasIdempotencyKey(idemKey)) {
            String at = ledger.mergedAt(packageVersion, targetVersion);
            if (at.isBlank()) at = "（已登记时间）";
            throw new AppException(ErrorCodes.E_PKG_003,
                    "该包已于 " + at + " 合并至版本 " + targetVersion
                            + "，重复导入被拒绝，不产生第二条合并记录")
                    .with("package_version", packageVersion)
                    .with("target_version", targetVersion)
                    .with("merged_at", at)
                    .hinting(ErrorCodes.hint(ErrorCodes.E_PKG_003));
        }

        review.requireReadyToMerge(); // 状态 REVIEWED 且全部条目已逐条确认
        List<PackageEntry> confirmed = review.getConfirmedEntries();
        java.util.Map<String, String> confirmers = review.getConfirmedConfirmers();

        String url = qcMetricsBaseUrl + "/api/v1/indicator-packages/"
                + OutboundRequest.encode(packageVersion) + "/merge";
        ObjectNode body = JsonSupport.object();
        body.put("package_version", packageVersion);
        body.put("target_version", targetVersion);
        body.put("target_definition_version", targetVersion);
        ArrayNode entries = body.putArray("confirmed_entries");
        for (PackageEntry e : confirmed) {
            ObjectNode item = entries.addObject();
            item.put("entry_no", e.getEntryNo());
            item.put("indicator_code", e.getIndicatorCode());
            item.put("indicator_name", e.getIndicatorName());
        }
        ObjectNode conf = body.putObject("confirmers");
        for (java.util.Map.Entry<String, String> en : confirmers.entrySet()) {
            conf.put(en.getKey(), en.getValue());
        }

        OutboundRequest.Builder rb = OutboundRequest.builder("POST", url)
                .jsonBody(JsonSupport.compact(body))
                .idempotencyKey(idemKey)
                .header("Accept", "application/json");
        if (!token.isBlank()) rb.bearer(token);
        OutboundRequest request = rb.build();

        OutboundResponse response = transport.send(request);
        if (response == null || !response.is2xx()) {
            int sc = response == null ? -1 : response.getStatusCode();
            String code = sc >= 500 ? ErrorCodes.E_OUTBOUND_004 : ErrorCodes.E_OUTBOUND_003;
            throw ErrorCodes.error(code, "status", String.valueOf(sc))
                    .with("endpoint", url)
                    .hinting("合并写端点未返回 2xx，本次合并未落库、未改变口径包状态");
        }

        String endpointId = parseEndpointId(response.getBody());
        MergeReceipt receipt = MergeReceipt.builder()
                .packageVersion(packageVersion)
                .targetVersion(targetVersion)
                .mergedAt(Instant.now().toString())
                .changedEntries(entryNos(confirmed))
                .confirmers(confirmers)
                .changed(true)
                .idempotencyKey(idemKey)
                .endpointReturnId(endpointId)
                .build();
        ledger.record(receipt);

        // 合并结果本地落库：本院口径新增对应版本，DRAFT 待复核，记来源包版本
        List<MetricDefinition> localDefs = new ArrayList<>();
        for (PackageEntry e : confirmed) {
            localDefs.add(MetricDefinition.builder(e.getIndicatorCode(), targetVersion)
                    .title(e.getIndicatorName())
                    .numerator(e.getNumeratorTree())
                    .denominator(e.getDenominatorTree())
                    .deptCodes(e.getDeptCodes())
                    .effectiveFrom(e.getEffectiveFrom())
                    .effectiveTo(e.getEffectiveTo())
                    .status(MetricStatus.DRAFT)
                    .dictionaryVersion("source:" + packageVersion)
                    .build());
        }
        ledger.saveLocalDefinitions(localDefs, packageVersion);

        review.markMerged(); // 合并成功才推进到 MERGED
        return receipt;
    }

    private static List<String> entryNos(List<PackageEntry> entries) {
        List<String> out = new ArrayList<>();
        for (PackageEntry e : entries) out.add(e.getEntryNo());
        return out;
    }

    private static String parseEndpointId(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) return "";
        try {
            JsonNode b = JsonSupport.parse(bodyText);
            if (b != null && b.isObject()) {
                for (String f : List.of("id", "merge_id", "receipt_id", "endpoint_id")) {
                    if (b.path(f).isTextual()) return b.path(f).asText();
                }
            }
        } catch (AppException ignored) {
            // 端点返回非 JSON 标识串：回执留空，不猜测修复
        }
        return "";
    }

    private static String trimTrailingSlash(String s) {
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
