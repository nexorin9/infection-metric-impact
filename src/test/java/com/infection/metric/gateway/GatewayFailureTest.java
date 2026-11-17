package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务46：五类错误码、超时重试、调用审计与不可达降级（发布状态不自动推进）。
 */
class GatewayFailureTest {

    /** 触发指定错误并断言：抛错、写入审计、审计元数据齐全、底层不吞异常。 */
    private static void callExpecting(FakeTransport fake, String task, JsonNode payload,
                                      int maxRetries, String expectedCode) {
        CallAudit audit = new CallAudit();
        ModelGateway gw = Fixtures.gateway(fake, audit, maxRetries);
        AppException ex = assertThrows(AppException.class, () -> gw.call(task, "1.0", payload));
        assertEquals(expectedCode, ex.getCode());
        assertFalse(ex.getMessage().isBlank(), "不吞异常，带可读文案");
        assertFalse(audit.withErrorCode(expectedCode).isEmpty(), "每类错误都写入调用审计");

        CallMetadata md = audit.withErrorCode(expectedCode).get(0);
        assertFalse(md.getModel().isBlank(), "审计元数据：模型名");
        assertFalse(md.getPromptVersion().isBlank(), "审计元数据：提示版本");
        assertFalse(md.getInputHash().isBlank(), "审计元数据：输入哈希");
        assertTrue(md.getElapsedMillis() >= 0, "审计元数据：耗时");
        assertEquals(expectedCode, md.getErrorCode(), "审计元数据：错误码");
        assertFalse(md.isDegraded(), "底层端口本身不标降级（降级在门面）");
    }

    @Test
    void 五类错误各有触发用例_都写审计且不吞异常() {
        ObjectNode payload = Fixtures.extractPayload();

        // 001 连接失败（替身抛 E_LLM_001）
        FakeTransport c1 = new FakeTransport();
        c1.appToThrow = ErrorCodes.error(ErrorCodes.E_LLM_001);
        callExpecting(c1, ExtractContract.TASK, payload, 0, ErrorCodes.E_LLM_001);

        // 002 超时
        FakeTransport c2 = new FakeTransport();
        c2.appToThrow = ErrorCodes.error(ErrorCodes.E_LLM_002);
        callExpecting(c2, ExtractContract.TASK, payload, 0, ErrorCodes.E_LLM_002);

        // 003 响应非 JSON
        FakeTransport c3 = new FakeTransport();
        c3.body = "this is not json <<<";
        callExpecting(c3, ExtractContract.TASK, payload, 0, ErrorCodes.E_LLM_003);

        // 004 schema 校验失败（契约外结构）
        FakeTransport c4 = new FakeTransport();
        c4.body = "{\"unexpected\":\"shape\"}";
        callExpecting(c4, ExtractContract.TASK, payload, 0, ErrorCodes.E_LLM_004);

        // 005 空结果
        FakeTransport c5 = new FakeTransport();
        c5.body = "{}";
        callExpecting(c5, ExtractContract.TASK, payload, 0, ErrorCodes.E_LLM_005);
    }

    @Test
    void 超时取LLM_TIMEOUT且重试上限LLM_MAX_RETRIES生效() {
        GatewayConfig cfg = Fixtures.config(7, 2);
        assertEquals(7000L, new HttpTransport(cfg).getTimeoutMillis(), "超时取 LLM_TIMEOUT");
        assertEquals(2, cfg.getMaxRetries(), "重试上限取 LLM_MAX_RETRIES");

        // 始终超时 → 1 次首次 + 2 次重试 = 3 次发送，最终抛 E_LLM_002
        FakeTransport alwaysTimeout = new FakeTransport();
        alwaysTimeout.appToThrow = ErrorCodes.error(ErrorCodes.E_LLM_002);
        CallAudit audit1 = new CallAudit();
        ModelGateway gw1 = Fixtures.gateway(alwaysTimeout, audit1, 2);
        assertEquals(ErrorCodes.E_LLM_002, assertThrows(AppException.class,
                () -> gw1.call(ExtractContract.TASK, "1.0", Fixtures.extractPayload())).getCode());
        assertEquals(3, alwaysTimeout.calls, "重试上限 2 → 共 3 次发送");
        assertEquals(2, audit1.all().get(0).getRetryCount(), "审计记录实际重试次数");

        // 上限 1 → 共 2 次发送
        FakeTransport limited = new FakeTransport();
        limited.appToThrow = ErrorCodes.error(ErrorCodes.E_LLM_001);
        ModelGateway gw2 = Fixtures.gateway(limited, new CallAudit(), 1);
        assertThrows(AppException.class,
                () -> gw2.call(ExtractContract.TASK, "1.0", Fixtures.extractPayload()));
        assertEquals(2, limited.calls, "重试上限 1 → 共 2 次发送");

        // 瞬时失败后成功：前 1 次超时，第 2 次返回合规结果
        FakeTransport transientFail = new FakeTransport();
        transientFail.failFirstN = 1;
        transientFail.failWith = ErrorCodes.error(ErrorCodes.E_LLM_002);
        transientFail.body = JsonSupport.compact(Fixtures.extractResponse());
        JsonNode result = Fixtures.gateway(transientFail, new CallAudit(), 2)
                .call(ExtractContract.TASK, "1.0", Fixtures.extractPayload());
        assertTrue(result.has("numerator"));
        assertEquals(2, transientFail.calls, "首次超时、重试一次后成功");
    }

    @Test
    void schema校验失败不重试() {
        FakeTransport fake = new FakeTransport();
        fake.body = "{\"unexpected\":\"shape\"}";
        CallAudit audit = new CallAudit();
        ModelGateway gw = Fixtures.gateway(fake, audit, 2); // 重试上限 2，但 schema 失败不应重试
        assertEquals(ErrorCodes.E_LLM_004, assertThrows(AppException.class,
                () -> gw.call(ExtractContract.TASK, "1.0", Fixtures.extractPayload())).getCode());
        assertEquals(1, fake.calls, "schema 校验失败不重试（同输入必然同结果）");
        assertEquals(0, audit.all().get(0).getRetryCount());
    }

    @Test
    void 不可达时降级且发布状态不推进() {
        FakeTransport unreachable = new FakeTransport();
        unreachable.appToThrow = ErrorCodes.error(ErrorCodes.E_LLM_001);
        CallAudit audit = new CallAudit();
        ModelGateway gw = Fixtures.gateway(unreachable, audit, 0);
        GatewayInvoker invoker = new GatewayInvoker(gw);

        CallOutcome outcome = invoker.invoke(ExtractContract.TASK, "1.0",
                Fixtures.extractPayload(), MetricStatus.DRAFT);

        assertTrue(outcome.isDegraded());
        assertEquals("人工", outcome.getSource());
        assertTrue(outcome.getSourceLabel().contains("口径来源 = 人工"));
        assertTrue(outcome.isManualInputRequired(), "退回人工录入条件树 + 规则校验");
        assertTrue(outcome.getNote().contains("人工录入条件树"), "降级说明含人工录入条件树");
        assertTrue(outcome.getNote().contains("规则校验"), "降级说明含规则校验");
        assertTrue(outcome.isModelOutputIsDraft());
        assertEquals(MetricStatus.DRAFT, outcome.getStatus(), "发布状态不自动推进，仍停留 DRAFT");
        assertNotEquals(MetricStatus.PUBLISHED, outcome.getStatus());
        assertNotEquals(MetricStatus.PENDING_REVIEW, outcome.getStatus());

        // 输入为 PENDING_REVIEW 时同样原样保留
        CallOutcome pending = invoker.invoke(ExtractContract.TASK, "1.0",
                Fixtures.extractPayload(), MetricStatus.PENDING_REVIEW);
        assertEquals(MetricStatus.PENDING_REVIEW, pending.getStatus());

        // 降级写入审计（是否降级 = true 且带错误码）
        assertTrue(audit.all().stream().anyMatch(CallMetadata::isDegraded));
        assertTrue(audit.all().stream().anyMatch(m -> m.isDegraded()
                && ErrorCodes.E_LLM_001.equals(m.getErrorCode())));

        // 内容类错误（schema 不合格）不降级、不吞异常
        FakeTransport badSchema = new FakeTransport();
        badSchema.body = "{\"unexpected\":\"shape\"}";
        GatewayInvoker strict = new GatewayInvoker(Fixtures.gateway(badSchema, new CallAudit(), 0));
        assertEquals(ErrorCodes.E_LLM_004, assertThrows(AppException.class,
                () -> strict.invoke(ExtractContract.TASK, "1.0",
                        Fixtures.extractPayload(), MetricStatus.DRAFT)).getCode());
    }

    @Test
    void 审计可按indicator_code反查全部模型调用() {
        FakeTransport fake = new FakeTransport();
        fake.body = JsonSupport.compact(Fixtures.extractResponse());
        CallAudit audit = new CallAudit();
        ModelGateway gw = Fixtures.gateway(fake, audit);

        ObjectNode p1 = ExtractContract.buildPayload("口径原文 A", "HAI-INC-01",
                List.of("dept_code"), "EMR");
        ObjectNode p2 = ExtractContract.buildPayload("口径原文 B", "HAI-INC-01",
                List.of("discharge_date"), "EMR");
        ObjectNode p3 = ExtractContract.buildPayload("口径原文 C", "MER-TQ-01",
                List.of("infection_site"), "HAI");
        gw.call(ExtractContract.TASK, "1.0", p1);
        gw.call(ExtractContract.TASK, "1.0", p2);
        gw.call(ExtractContract.TASK, "1.0", p3);

        List<CallMetadata> hai = audit.findByIndicatorCode("HAI-INC-01");
        assertEquals(2, hai.size());
        for (CallMetadata m : hai) {
            assertEquals("HAI-INC-01", m.getIndicatorCode());
            assertFalse(m.getModel().isBlank());
            assertFalse(m.getPromptVersion().isBlank());
            assertFalse(m.getInputHash().isBlank());
            assertNull(m.getErrorCode());
            assertFalse(m.isDegraded());
        }
        assertEquals(1, audit.findByIndicatorCode("MER-TQ-01").size());
        assertTrue(audit.findByIndicatorCode("NOT-EXIST").isEmpty());

        // 同一 indicator_code 的失败调用与降级决策也在反查链里
        FakeTransport bad = new FakeTransport();
        bad.appToThrow = ErrorCodes.error(ErrorCodes.E_LLM_001);
        new GatewayInvoker(Fixtures.gateway(bad, audit, 0))
                .invoke(ExtractContract.TASK, "1.0", p1, MetricStatus.DRAFT);
        List<CallMetadata> full = audit.findByIndicatorCode("HAI-INC-01");
        assertEquals(4, full.size(), "含两次成功 + 失败调用 + 降级决策");
        assertTrue(full.stream().anyMatch(CallMetadata::isDegraded));
        assertTrue(full.stream().anyMatch(m -> ErrorCodes.E_LLM_001.equals(m.getErrorCode())));
    }

    @Test
    void 模型成功时口径来源为模型_结果仍是草稿_状态不推进() {
        FakeTransport fake = new FakeTransport();
        fake.body = JsonSupport.compact(Fixtures.extractResponse());
        GatewayInvoker invoker = new GatewayInvoker(Fixtures.gateway(fake));

        CallOutcome outcome = invoker.invoke(ExtractContract.TASK, "1.0",
                Fixtures.extractPayload(), MetricStatus.PENDING_REVIEW);

        assertFalse(outcome.isDegraded());
        assertTrue(outcome.isSuccess());
        assertEquals("模型", outcome.getSource());
        assertTrue(outcome.isModelOutputIsDraft(), "模型输出永远是草稿");
        assertEquals(MetricStatus.PENDING_REVIEW, outcome.getStatus(), "成功也不自动推进发布状态");
        assertTrue(outcome.getPayload().has("numerator"));
    }
}
