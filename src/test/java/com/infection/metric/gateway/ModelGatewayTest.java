package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.common.OutboundRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务11：模型网关内核——配置面、结构化请求封装、OpenAI-compatible 协议兼容、
 * 本地 schema 校验基础与调用元数据骨架。
 */
class ModelGatewayTest {

    @Test
    void 配置默认值与显式值() {
        GatewayConfig def = GatewayConfig.of("https://host", "k", "m");
        assertEquals(30, def.getTimeoutSeconds(), "LLM_TIMEOUT 默认 30s");
        assertEquals(2, def.getMaxRetries(), "LLM_MAX_RETRIES 默认 2");
        assertEquals("1.0", def.getPromptVersion());
        assertEquals(30_000L, def.getTimeoutMillis());

        GatewayConfig custom = GatewayConfig.of("https://host", "k", "m", 7, 4);
        assertEquals(7, custom.getTimeoutSeconds());
        assertEquals(4, custom.getMaxRetries());
        assertEquals(7000L, custom.getTimeoutMillis());

        // fromEnvironment 走 EnvConfig，结果结构合法（不依赖具体环境变量取值）
        GatewayConfig env = GatewayConfig.fromEnvironment();
        assertTrue(env.getTimeoutSeconds() > 0);
        assertTrue(env.getMaxRetries() >= 0);
        assertEquals("1.0", env.getPromptVersion());
    }

    @Test
    void 请求体固定三字段且按OpenAI兼容协议组装头() {
        FakeTransport fake = new FakeTransport();
        fake.body = JsonSupport.compact(Fixtures.extractResponse());
        ModelGateway gw = Fixtures.gateway(fake);
        JsonNode result = gw.call(ExtractContract.TASK, "1.0", Fixtures.extractPayload());

        assertTrue(result.has("numerator"), "合规响应被解析为契约对象");

        OutboundRequest req = fake.last;
        assertEquals("POST", req.getMethod());
        assertEquals(Fixtures.config().endpointUrl(), req.getUrl());
        assertEquals("Bearer sk-test-key", req.getHeaders().get("Authorization"));
        assertEquals("application/json", req.getHeaders().get("Content-Type"));
        assertEquals("application/json", req.getHeaders().get("Accept"));
        assertEquals("test-model-1", req.getHeaders().get("X-LLM-Model"));

        JsonNode body = JsonSupport.parse(req.getBody());
        assertEquals(3, body.size(), "结构化请求体固定为 task / schema_version / payload");
        assertEquals("extract_definition", body.path("task").asText());
        assertEquals("1.0", body.path("schema_version").asText());
        assertEquals("HAI-INC-01", body.path("payload").path("indicator_code").asText());
        assertFalse(body.has("model"), "业务层不引用厂商 SDK，不往请求体塞 model/messages");
        assertFalse(body.has("messages"));
    }

    @Test
    void base_url拼接默认端点与自定义路径_替换即切换服务方() {
        assertEquals("https://api.openai.com/v1/chat/completions",
                GatewayConfig.of("https://api.openai.com", "k", "m").endpointUrl());
        assertEquals("https://api.openai.com/v1/chat/completions",
                GatewayConfig.of("https://api.openai.com/", "k", "m").endpointUrl());
        assertEquals("http://localhost:9999/v1/structured/extract",
                GatewayConfig.of("http://localhost:9999/v1/structured/extract", "k", "m").endpointUrl());

        FakeTransport f1 = new FakeTransport();
        f1.body = JsonSupport.compact(Fixtures.extractResponse());
        new ModelGateway(GatewayConfig.of("https://api.openai.com", "k", "m"), f1, new CallAudit())
                .call(ExtractContract.TASK, "1.0", Fixtures.extractPayload());

        FakeTransport f2 = new FakeTransport();
        f2.body = JsonSupport.compact(Fixtures.extractResponse());
        new ModelGateway(GatewayConfig.of("https://another.vendor/v1/custom", "k", "m"), f2, new CallAudit())
                .call(ExtractContract.TASK, "1.0", Fixtures.extractPayload());

        assertEquals("https://api.openai.com/v1/chat/completions", f1.last.getUrl());
        assertEquals("https://another.vendor/v1/custom", f2.last.getUrl());
        assertNotEquals(f1.last.getUrl(), f2.last.getUrl(), "替换 base_url 即切换服务方");
    }

    @Test
    void 响应解析兼容直接契约与OpenAI信封_产品代码不只为替身定制() {
        String contract = JsonSupport.compact(Fixtures.extractResponse());

        FakeTransport direct = new FakeTransport();
        direct.body = contract;
        JsonNode r1 = Fixtures.gateway(direct)
                .call(ExtractContract.TASK, "1.0", Fixtures.extractPayload());

        FakeTransport enveloped = new FakeTransport();
        enveloped.body = Fixtures.envelope(contract);
        JsonNode r2 = Fixtures.gateway(enveloped)
                .call(ExtractContract.TASK, "1.0", Fixtures.extractPayload());

        assertEquals(r1, r2, "直接契约与 OpenAI-compatible choices 信封解析结果一致");
        assertTrue(r1.has("numerator") && r2.has("citations"));

        // 默认构造使用真实 HttpClient，而非只适配替身
        assertTrue(new ModelGateway().getTransport() instanceof HttpTransport);
    }

    @Test
    void 本地schema校验基础_标量或非JSON响应失败_合法容器通过() {
        FakeTransport scalar = new FakeTransport();
        scalar.body = "42";
        ModelGateway gw1 = new ModelGateway(Fixtures.config(), scalar, new CallAudit(), Map.of());
        assertEquals(ErrorCodes.E_LLM_003, assertThrows(AppException.class,
                () -> gw1.call("custom_task", "1.0", Fixtures.extractPayload())).getCode());

        FakeTransport nonJson = new FakeTransport();
        nonJson.body = "这是一段纯文本，不是 JSON <<<";
        ModelGateway gw2 = new ModelGateway(Fixtures.config(), nonJson, new CallAudit(), Map.of());
        assertEquals(ErrorCodes.E_LLM_003, assertThrows(AppException.class,
                () -> gw2.call("custom_task", "1.0", Fixtures.extractPayload())).getCode());

        FakeTransport ok = new FakeTransport();
        ok.body = "{\"ok\":true,\"value\":1}";
        JsonNode result = new ModelGateway(Fixtures.config(), ok, new CallAudit(), Map.of())
                .call("custom_task", "1.0", Fixtures.extractPayload());
        assertTrue(result.path("ok").asBoolean(), "合法 JSON 容器通过本地 schema 基础校验");
        assertEquals(1, result.path("value").asInt());
    }

    @Test
    void 调用元数据骨架_模型名提示版本输入哈希耗时写入审计() {
        FakeTransport fake = new FakeTransport();
        fake.body = JsonSupport.compact(Fixtures.extractResponse());
        CallAudit audit = new CallAudit();
        ModelGateway gw = Fixtures.gateway(fake, audit);

        gw.call(ExtractContract.TASK, "1.0", Fixtures.extractPayload());
        gw.call(ExtractContract.TASK, "1.0", Fixtures.extractPayload());

        assertEquals(2, audit.size());
        CallMetadata first = audit.all().get(0);
        CallMetadata second = audit.all().get(1);
        assertEquals("test-model-1", first.getModel(), "元数据：模型名");
        assertEquals("1.0", first.getPromptVersion(), "元数据：提示版本");
        assertFalse(first.getInputHash().isBlank(), "元数据：输入哈希");
        assertEquals(64, first.getInputHash().length(), "输入哈希为 sha256 十六进制");
        assertTrue(first.getElapsedMillis() >= 0, "元数据：耗时");
        assertEquals("HAI-INC-01", first.getIndicatorCode());
        assertTrue(first.isSuccess());
        assertNull(first.getErrorCode());
        assertFalse(first.isDegraded());
        assertEquals(first.getInputHash(), second.getInputHash(), "同输入必然同哈希");
    }
}
