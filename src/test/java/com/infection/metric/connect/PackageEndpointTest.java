package com.infection.metric.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.common.OutboundResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 任务41：口径包读取与合并两端点契约。 */
class PackageEndpointTest {

    private PackageEndpointClient client(EndpointTestFakes.RecordingTransport t) {
        return new PackageEndpointClient(t, "https://regional.test", "https://qc.test");
    }

    @Test
    void fetchPackageContract() {
        EndpointTestFakes.RecordingTransport t = new EndpointTestFakes.RecordingTransport(
                OutboundResponse.of(200, "{\"package_version\":\"PKG-2026.1\",\"entries\":[]}"));
        JsonNode pkg = client(t).fetchPackage("PKG-2026.1");
        assertEquals("GET", t.last().getMethod());
        assertTrue(t.last().getUrl().contains("/api/v1/indicator-packages/PKG-2026.1"));
        assertTrue(t.last().getHeaders().containsKey("Authorization"));
        assertEquals("PKG-2026.1", pkg.path("package_version").asText());
    }

    @Test
    void mergeRequestContractAndIdempotencyKey() {
        EndpointTestFakes.RecordingTransport t = new EndpointTestFakes.RecordingTransport(
                OutboundResponse.of(200, "{\"merged\":true}"));
        client(t).merge("PKG-2026.1", List.of("E01", "E02"),
                Map.of("E01", "张主任", "E02", "李主任"), "2026.2");
        assertEquals("POST", t.last().getMethod());
        assertTrue(t.last().getUrl().contains("/api/v1/indicator-packages/PKG-2026.1/merge"));
        assertEquals(IdempotencyKeys.merge("PKG-2026.1", "2026.2"),
                t.last().getHeaders().get("Idempotency-Key"));
        assertTrue(t.last().getHeaders().containsKey("Authorization"));
        JsonNode body = JsonSupport.parse(t.last().getBody());
        assertEquals(2, body.path("confirmed_entries").size());
        assertEquals("张主任", body.path("confirmed_by").path("E01").asText());
        assertEquals("2026.2", body.path("target_definition_version").asText());
    }

    @Test
    void specialCharactersInPackageVersionAreEncoded() {
        EndpointTestFakes.RecordingTransport t = new EndpointTestFakes.RecordingTransport(
                OutboundResponse.of(200, "{}"));
        client(t).fetchPackage("PKG 2026/1");
        assertFalse(t.last().getUrl().contains(" "), "路径段须 URL 编码");
        assertTrue(t.last().getUrl().contains("PKG+2026"));
    }
}
