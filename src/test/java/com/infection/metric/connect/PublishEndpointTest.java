package com.infection.metric.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.common.OutboundResponse;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务40：发布端点契约、幂等键、回执反查、前置校验、重复发布仍 1 条版本。 */
class PublishEndpointTest {

    private MetricDefinition pending() {
        return MetricDefinition.builder("MER-TL-03", "2026.2")
                .title("出院记录24小时内完成率")
                .numerator(ConditionTree.leaf("quarter", "eq", "2026Q1"))
                .denominator(ConditionTree.leaf("quarter", "eq", "2026Q1"))
                .deptCodes(List.of("0301"))
                .status(MetricStatus.PENDING_REVIEW)
                .effectiveFrom("2026-01-01")
                .build();
    }

    private PublishClient.PreChecks okChecks() {
        return new PublishClient.PreChecks() {
            public boolean fieldReconcilePassed() { return true; }
            public boolean dependencyGraphAcyclic() { return true; }
        };
    }

    @Test
    void publishRequestContract(@TempDir Path dir) {
        EndpointTestFakes.RecordingTransport transport = new EndpointTestFakes.RecordingTransport(
                OutboundResponse.of(201, "{\"id\":\"DEF-889\",\"changed\":true}"));
        PublishClient client = new PublishClient(transport, "https://qc.test",
                new PublishClient.ReceiptStore(dir.resolve("receipts.json")), okChecks());
        client.publish(pending(), List.of("分子范围改窄"));
        assertEquals("POST", transport.last().getMethod());
        assertTrue(transport.last().getUrl().endsWith("/api/v1/indicator-definitions"));
        assertEquals(IdempotencyKeys.publish("MER-TL-03", "2026.2"),
                transport.last().getHeaders().get("Idempotency-Key"));
        assertTrue(transport.last().getHeaders().containsKey("Authorization"));
        JsonNode body = JsonSupport.parse(transport.last().getBody());
        assertEquals("MER-TL-03", body.path("indicator_code").asText());
        assertEquals("2026.2", body.path("definition_version").asText());
        assertTrue(body.has("numerator") && body.has("denominator"));
        assertTrue(body.has("data_source_fields") && body.has("effective_from"));
        assertEquals("PENDING_REVIEW", body.path("status").asText());
    }

    @Test
    void repeatedPublishKeepsOnePublishedVersion(@TempDir Path dir) {
        EndpointTestFakes.RecordingTransport transport = new EndpointTestFakes.RecordingTransport(
                OutboundResponse.of(201, "{\"id\":\"DEF-889\",\"changed\":true}"));
        PublishClient.ReceiptStore store = new PublishClient.ReceiptStore(dir.resolve("receipts.json"));
        PublishClient client = new PublishClient(transport, "https://qc.test", store, okChecks());
        client.publish(pending(), List.of("分子范围改窄"));
        client.publish(pending(), List.of("分子范围改窄"));
        assertEquals(1, store.publishedVersionCount("MER-TL-03"));
        assertNotNull(store.find("MER-TL-03"));
        assertEquals("DEF-889", store.find("MER-TL-03").endpointReturnedId());
    }

    @Test
    void preChecksFailureBlocksRequest(@TempDir Path dir) {
        EndpointTestFakes.RecordingTransport transport = new EndpointTestFakes.RecordingTransport();
        PublishClient.PreChecks failing = new PublishClient.PreChecks() {
            public boolean fieldReconcilePassed() { return false; }
            public boolean dependencyGraphAcyclic() { return true; }
        };
        PublishClient client = new PublishClient(transport, "https://qc.test",
                new PublishClient.ReceiptStore(dir.resolve("r.json")), failing);
        AppException ex = assertThrows(AppException.class, () -> client.publish(pending(), List.of()));
        assertEquals(ErrorCodes.E_PUBLISH_001, ex.getCode());
        assertEquals(0, transport.count(), "前置未通过时根本不发请求");
    }

    @Test
    void wrongStatusBlocksPublish(@TempDir Path dir) {
        EndpointTestFakes.RecordingTransport transport = new EndpointTestFakes.RecordingTransport();
        PublishClient client = new PublishClient(transport, "https://qc.test",
                new PublishClient.ReceiptStore(dir.resolve("r.json")), okChecks());
        MetricDefinition draft = pending().toBuilder().status(MetricStatus.DRAFT).build();
        assertThrows(AppException.class, () -> client.publish(draft, List.of()));
        assertEquals(0, transport.count());
    }

    @Test
    void receiptPersistsAcrossStoreReopen(@TempDir Path dir) {
        EndpointTestFakes.RecordingTransport transport = new EndpointTestFakes.RecordingTransport(
                OutboundResponse.of(201, "{\"id\":\"DEF-889\",\"changed\":true}"));
        Path file = dir.resolve("receipts.json");
        new PublishClient(transport, "https://qc.test", new PublishClient.ReceiptStore(file), okChecks())
                .publish(pending(), List.of("分子范围改窄"));
        PublishClient.ReceiptStore reopened = new PublishClient.ReceiptStore(file);
        assertEquals(1, reopened.size());
        assertTrue(reopened.contains("MER-TL-03", "2026.2"));
    }
}
