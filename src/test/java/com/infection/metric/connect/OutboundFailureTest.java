package com.infection.metric.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 任务42：4xx 不重试 / 5xx 重试 3 次后失败 / 失败进入摘要 / 日志不含令牌。 */
class OutboundFailureTest {

    @Test
    void client4xxNotRetriedAndHasDedicatedCode() {
        int[] calls = {0};
        RetryPolicy policy = new RetryPolicy(3, millis -> {});
        OutboundClient client = new OutboundClient(null, policy, (req, url) -> {
            calls[0]++;
            return OutboundClient.RawResult.ok(404, "{\"error\":\"not found\"}");
        });
        AppException ex = assertThrows(AppException.class, () ->
                client.send(OutboundRequest.builder("GET", "https://emr.test/api/v1/encounters").build()));
        assertEquals(ErrorCodes.E_OUTBOUND_003, ex.getCode());
        assertEquals(1, calls[0]);
    }

    @Test
    void server5xxRetriesThreeTimesThenFails() {
        int[] calls = {0};
        RetryPolicy policy = new RetryPolicy(3, millis -> {});
        OutboundClient client = new OutboundClient(null, policy, (req, url) -> {
            calls[0]++;
            return OutboundClient.RawResult.ok(500, "{\"error\":\"down\"}");
        });
        AppException ex = assertThrows(AppException.class, () ->
                client.send(OutboundRequest.builder("GET", "https://qc.test/api/v1/indicator-definitions").build()));
        assertEquals(ErrorCodes.E_OUTBOUND_004, ex.getCode());
        assertEquals(4, calls[0]);
        assertEquals(3, policy.getIntervals().size());
    }

    @Test
    void timeoutAndConnectionFailureHaveDistinctCodes() {
        RetryPolicy p1 = new RetryPolicy(0, millis -> {});
        OutboundClient timedOut = new OutboundClient(null, p1,
                (req, url) -> OutboundClient.RawResult.timeout());
        assertEquals(ErrorCodes.E_OUTBOUND_002, assertThrows(AppException.class, () ->
                timedOut.send(OutboundRequest.builder("GET", "https://x.test").build())).getCode());
        RetryPolicy p2 = new RetryPolicy(0, millis -> {});
        OutboundClient connFail = new OutboundClient(null, p2,
                (req, url) -> OutboundClient.RawResult.connectionFailure());
        assertEquals(ErrorCodes.E_OUTBOUND_001, assertThrows(AppException.class, () ->
                connFail.send(OutboundRequest.builder("GET", "https://x.test").build())).getCode());
    }

    @Test
    void failuresEnterSummaryWithNullNumbersNotZero() {
        OutboundFailureLog log = new OutboundFailureLog();
        OutboundRequest req = OutboundRequest.builder("GET", "https://emr.test/api/v1/encounters")
                .bearer("SUPER-SECRET-123").build();
        AppException err = ErrorCodes.error(ErrorCodes.E_OUTBOUND_004, "status", "503");
        log.recordFailure(req, err);
        log.bridgeToSummary("MER-TL-03", "取数", err);
        assertEquals(1, log.failedSteps().size());
        assertNull(log.failedSteps().get(0).getNumerator(), "失败不按 0 计入分母");
        assertNull(log.failedSteps().get(0).getDenominator());
        assertEquals(1L, log.summaryFragment().get("outbound_failures"));
        JsonNode step = log.failedSteps().get(0).toJson();
        assertTrue(step.path("numerator").isNull());
        assertEquals("FAILED", step.path("status").asText());
    }

    @Test
    void structuredLogNeverContainsTokenPlaintext() {
        OutboundFailureLog log = new OutboundFailureLog();
        OutboundRequest req = OutboundRequest.builder("GET", "https://emr.test/api/v1/encounters")
                .bearer("SUPER-SECRET-123").build();
        log.record(req, OutboundResponse.of(200, "{}"));
        assertFalse(log.entries().get(0).getEndpoint().contains("SUPER-SECRET-123"));
        assertFalse(OutboundFailureLog.sanitize("Authorization: Bearer SUPER-SECRET-123").contains("SUPER-SECRET-123"));
        assertTrue(OutboundFailureLog.sanitize("Authorization: Bearer SUPER-SECRET-123").contains("***"));
    }
}
