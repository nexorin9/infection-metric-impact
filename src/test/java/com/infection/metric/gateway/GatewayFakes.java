package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;
import com.infection.metric.common.OutboundTransport;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试替身与夹具：本地 OpenAI-compatible 协议替身，记录请求、可配置响应体 / 状态 /
 * 抛错 / 前 N 次失败。本文件无 @Test，不参与 surefire 运行。
 */
final class FakeTransport implements OutboundTransport {

    String body = "{}";
    int status = 200;
    AppException appToThrow;
    RuntimeException rawToThrow;
    int failFirstN = 0;
    AppException failWith;
    int calls = 0;
    OutboundRequest last;
    final List<OutboundRequest> requests = new ArrayList<>();

    @Override
    public OutboundResponse send(OutboundRequest request) {
        calls++;
        last = request;
        requests.add(request);
        if (failFirstN > 0) {
            failFirstN--;
            throw failWith != null ? failWith : ErrorCodes.error(ErrorCodes.E_LLM_002);
        }
        if (appToThrow != null) {
            throw appToThrow;
        }
        if (rawToThrow != null) {
            throw rawToThrow;
        }
        return OutboundResponse.of(status, body);
    }
}

/** 契约请求 / 响应夹具，供各测试类复用。 */
final class Fixtures {

    private Fixtures() {}

    static GatewayConfig config() { return config(5, 2); }

    static GatewayConfig config(int timeoutSeconds, int maxRetries) {
        return GatewayConfig.of("https://llm.test.internal/v1/chat/completions",
                "sk-test-key", "test-model-1", timeoutSeconds, maxRetries);
    }

    static ModelGateway gateway(FakeTransport t) {
        return new ModelGateway(config(), t, new CallAudit());
    }

    static ModelGateway gateway(FakeTransport t, CallAudit audit) {
        return new ModelGateway(config(), t, audit);
    }

    static ModelGateway gateway(FakeTransport t, CallAudit audit, int maxRetries) {
        return new ModelGateway(config(5, maxRetries), t, audit);
    }

    static ObjectNode extractPayload() {
        return ExtractContract.buildPayload(
                "出院日期在 2026 年第一季度，且感染部位为肺部者计入分子",
                "HAI-INC-01",
                List.of("dept_code", "discharge_date", "infection_site"),
                "EMR");
    }

    static ObjectNode extractResponse() {
        return ExtractContract.sampleResponse();
    }

    static ObjectNode aliasPayload() {
        return AliasContract.buildPayload("MER-TQ-01", List.of("c1", "c2", "c3"));
    }

    static ArrayNode aliasResponse() {
        return AliasContract.sampleResponse("c1", "c2");
    }

    static ObjectNode attributePayload() {
        return AttributeContract.buildPayload("MER-TQ-01", List.of("d1", "d2"));
    }

    static ArrayNode attributeResponse() {
        return AttributeContract.sampleResponse("NUMERATOR_SCOPE", "d1");
    }

    /** OpenAI-compatible choices 信封：message.content 为契约 JSON 文本。 */
    static String envelope(String innerJson) {
        ObjectNode root = JsonSupport.object();
        ArrayNode choices = root.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        message.put("content", innerJson);
        return JsonSupport.compact(root);
    }
}
