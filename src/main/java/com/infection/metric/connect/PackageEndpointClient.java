package com.infection.metric.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.EnvConfig;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;
import com.infection.metric.common.OutboundTransport;

import java.util.List;
import java.util.Map;

/** 上级口径包读取与合并写端点：GET /indicator-packages/{v} 与 POST /indicator-packages/{v}/merge。 */
public final class PackageEndpointClient {

    private final OutboundTransport transport;
    private final String regionalBaseUrl;
    private final String qcBaseUrl;

    public PackageEndpointClient(OutboundTransport transport, String regionalBaseUrl, String qcBaseUrl) {
        this.transport = transport;
        this.regionalBaseUrl = trim(regionalBaseUrl);
        this.qcBaseUrl = trim(qcBaseUrl);
    }

    private static String trim(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public JsonNode fetchPackage(String packageVersion) {
        String path = "/api/v1/indicator-packages/" + encode(packageVersion);
        OutboundRequest req = OutboundRequest.builder("GET", regionalBaseUrl + path)
                .bearer(token("REGIONAL_TOKEN"))
                .build();
        return execute(req);
    }

    public JsonNode merge(String packageVersion, List<String> confirmedEntryIds,
                          Map<String, String> confirmers, String targetDefinitionVersion) {
        String path = "/api/v1/indicator-packages/" + encode(packageVersion) + "/merge";
        String key = IdempotencyKeys.merge(packageVersion, targetDefinitionVersion);
        ObjectNode body = JsonSupport.object();
        ArrayNode entries = body.putArray("confirmed_entries");
        for (String id : confirmedEntryIds) entries.add(id);
        ObjectNode who = body.putObject("confirmed_by");
        confirmers.forEach(who::put);
        body.put("target_definition_version", targetDefinitionVersion);
        body.put("package_version", packageVersion);

        OutboundRequest req = OutboundRequest.builder("POST", qcBaseUrl + path)
                .bearer(token("QC_METRICS_TOKEN"))
                .idempotencyKey(key)
                .jsonBody(JsonSupport.pretty(body))
                .build();
        return execute(req);
    }

    private JsonNode execute(OutboundRequest req) {
        OutboundResponse resp = transport.send(req);
        if (!resp.is2xx()) {
            throw FailureClassifier.error(FailureClassifier.of(resp.getStatusCode(), false, false),
                    req.getUrl(), resp.getStatusCode());
        }
        return JsonSupport.parse(resp.getBody().isBlank() ? "{}" : resp.getBody());
    }

    private static String token(String key) {
        String t = EnvConfig.fromEnvironment().get(key);
        return t == null ? "" : t;
    }

    private static String encode(String s) {
        return OutboundRequest.encode(s);
    }
}
