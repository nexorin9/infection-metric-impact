package com.infection.metric.connect;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务10：URL 拼装、请求头注入、幂等键、重试上限、4xx 不重试。 */
class OutboundTest {

    @Test
    void urlAssemblyAndQueryEncoding() {
        OutboundRequest req = OutboundRequest.builder("GET", "https://emr.test/api/v1/encounters")
                .query("dischargeFrom", "2026-01-01")
                .query("deptCode", "0301 0302")
                .build();
        assertTrue(req.fullUrl().contains("dischargeFrom=2026-01-01"));
        assertTrue(req.fullUrl().contains("0301+0302"), "空格须 URL 编码为 +");
        assertEquals("GET", req.getMethod());
    }

    @Test
    void bearerHeaderInjectionAndNoTokenInLog() {
        OutboundRequest req = OutboundRequest.builder("POST", "https://qc.test/api/v1/indicator-definitions")
                .bearer("SECRET-TOKEN-ABC")
                .idempotencyKey("IDEMP-1")
                .jsonBody("{\"a\":1}")
                .build();
        assertEquals("Bearer SECRET-TOKEN-ABC", req.getHeaders().get("Authorization"));
        assertEquals("IDEMP-1", req.getHeaders().get("Idempotency-Key"));
        assertEquals("application/json", req.getHeaders().get("Content-Type"));
        assertFalse(OutboundFailureLog.sanitize("Authorization: Bearer SECRET-TOKEN-ABC").contains("SECRET-TOKEN-ABC"));
    }

    @Test
    void idempotencyKeysAreStableAndDisjoint() {
        String p1 = IdempotencyKeys.publish("HAI-INC-01", "2026.1");
        String p2 = IdempotencyKeys.publish("HAI-INC-01", "2026.1");
        String p3 = IdempotencyKeys.publish("HAI-INC-01", "2026.2");
        String m1 = IdempotencyKeys.merge("PKG-2026.1", "2026.2");
        assertEquals(p1, p2, "同键多次计算结果稳定");
        assertNotEquals(p1, p3, "不同版本不同键");
        assertNotEquals(p1, m1, "发布键与合并键值域互不相同");
        assertEquals(64, m1.length());
    }

    @Test
    void retriable5xxRetriesUpToThreeTimes() {
        List<OutboundClient.RawResult> script = new ArrayList<>();
        for (int i = 0; i < 4; i++) script.add(OutboundClient.RawResult.ok(503, "{\"error\":\"busy\"}"));
        int[] calls = {0};
        RetryPolicy policy = new RetryPolicy(3, millis -> {});
        OutboundClient client = new OutboundClient(null, policy, (req, url) -> script.get(calls[0]++));
        AppException ex = assertThrows(AppException.class, () ->
                client.send(OutboundRequest.builder("GET", "https://x.test/api").build()));
        assertEquals(ErrorCodes.E_OUTBOUND_004, ex.getCode());
        assertEquals(4, calls[0], "首次 + 3 次重试");
        assertEquals(3, policy.retryCount());
        assertEquals(List.of(50L, 100L, 200L), policy.getIntervals(), "指数退避间隔");
    }

    @Test
    void client4xxNeverRetries() {
        int[] calls = {0};
        RetryPolicy policy = new RetryPolicy(3, millis -> {});
        OutboundClient client = new OutboundClient(null, policy, (req, url) -> {
            calls[0]++;
            return OutboundClient.RawResult.ok(400, "{\"error\":\"bad request\"}");
        });
        AppException ex = assertThrows(AppException.class, () ->
                client.send(OutboundRequest.builder("POST", "https://x.test/api").build()));
        assertEquals(ErrorCodes.E_OUTBOUND_003, ex.getCode());
        assertEquals(1, calls[0], "4xx 不重试");
        assertEquals(0, policy.retryCount());
        assertFalse(FailureClassifier.retryable(FailureClassifier.Kind.CLIENT_4XX));
    }

    @Test
    void successResponseCarriesRetryCount() {
        RetryPolicy policy = new RetryPolicy(3, millis -> {});
        OutboundClient client = new OutboundClient(null, policy,
                (req, url) -> OutboundClient.RawResult.ok(200, "{\"ok\":true}"));
        OutboundResponse resp = client.send(OutboundRequest.builder("GET", "https://x.test/api").build());
        assertEquals(200, resp.getStatusCode());
        assertTrue(resp.is2xx());
        assertEquals(0, resp.getRetryCount());
    }
}
