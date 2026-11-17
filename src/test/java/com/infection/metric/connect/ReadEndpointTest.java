package com.infection.metric.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.OutboundResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 任务38：读取端点方法/路径/查询参数/请求头契约 + 字段映射 + 缓存命中。 */
class ReadEndpointTest {

    @Test
    void encounterRequestContract() {
        EndpointTestFakes.RecordingTransport transport = new EndpointTestFakes.RecordingTransport(
                OutboundResponse.of(200, "{\"encounters\":[{\"encounterId\":\"E1\",\"dischargeDate\":\"2026-01-05\",\"deptCode\":\"0301\",\"primaryDiagnosisCode\":\"A09\",\"procedureCode\":\"47.01\",\"woundClass\":\"II\"}]}"));
        ReadEndpointClient client = new ReadEndpointClient(transport,
                "https://emr.test", "https://hai.test", "EMR_API_TOKEN", "HAI_API_TOKEN",
                new LocalReadCache(), "v1");
        JsonNode result = client.fetchEncounters("2026-01-01", "2026-03-31", "0301");
        assertEquals("GET", transport.last().getMethod());
        assertTrue(transport.last().getUrl().endsWith("/api/v1/encounters"));
        assertEquals("2026-01-01", transport.last().getQuery().get("dischargeFrom"));
        assertEquals("2026-03-31", transport.last().getQuery().get("dischargeTo"));
        assertEquals("0301", transport.last().getQuery().get("deptCode"));
        assertEquals(1, result.path("count").asInt());
        assertTrue(result.path("encounters").get(0).has("就诊标识"), "外部字段须映射为内部中文业务名");
    }

    @Test
    void infectionReportRequestContractAndFieldMapping() {
        EndpointTestFakes.RecordingTransport transport = new EndpointTestFakes.RecordingTransport(
                OutboundResponse.of(200, "{\"infection_reports\":[{\"infectionSite\":\"呼吸道\",\"pathogen\":\"大肠埃希菌\",\"relatedSurgeryDate\":\"2026-01-05\",\"mdrFlag\":true,\"deptCode\":\"0301\",\"onsetDate\":\"2026-01-06\"}]}"));
        ReadEndpointClient client = new ReadEndpointClient(transport,
                "https://emr.test", "https://hai.test", "EMR_API_TOKEN", "HAI_API_TOKEN",
                new LocalReadCache(), "v1");
        JsonNode result = client.fetchInfectionReports("2026Q1");
        assertTrue(transport.last().getUrl().endsWith("/api/v1/infection-reports"));
        assertEquals("2026Q1", transport.last().getQuery().get("period"));
        assertTrue(result.path("infection_reports").get(0).has("多重耐药菌标志"));
        assertTrue(result.path("infection_reports").get(0).has("感染部位"));
    }

    @Test
    void repeatedReadHitsCacheAndDoesNotRefetch() {
        EndpointTestFakes.RecordingTransport transport = new EndpointTestFakes.RecordingTransport(
                OutboundResponse.of(200, "{\"encounters\":[]}"));
        LocalReadCache cache = new LocalReadCache();
        ReadEndpointClient client = new ReadEndpointClient(transport,
                "https://emr.test", "https://hai.test", "EMR_API_TOKEN", "HAI_API_TOKEN", cache, "v1");
        client.fetchEncounters("2026-01-01", "2026-03-31", null);
        client.fetchEncounters("2026-01-01", "2026-03-31", null);
        assertEquals(1, transport.count(), "同期间重复读取命中缓存，不再取数");
        assertEquals(1, cache.hitCount());
        assertEquals(1, cache.missCount());
    }

    @Test
    void fieldDictionaryEndpointContract() {
        EndpointTestFakes.RecordingTransport transport = new EndpointTestFakes.RecordingTransport(
                OutboundResponse.of(200, "{\"version\":\"v2\",\"fields\":[{\"field\":\"dept_code\",\"type\":\"string\",\"entity\":\"encounter\",\"required\":true}]}"));
        ReadEndpointClient client = new ReadEndpointClient(transport,
                "https://emr.test", "https://hai.test", "EMR_API_TOKEN", "HAI_API_TOKEN",
                new LocalReadCache(), "v1");
        JsonNode dict = client.fetchFieldDictionary();
        assertTrue(transport.last().getUrl().endsWith("/api/v1/metadata/fields"));
        assertEquals("v2", dict.path("version").asText());
        assertEquals(1, FieldReconciler.dictionaryFrom(dict).size());
    }

    @Test
    void missingFieldsAreCountedNotSilentlyDropped() {
        EndpointTestFakes.RecordingTransport transport = new EndpointTestFakes.RecordingTransport(
                OutboundResponse.of(200, "{\"encounters\":[{\"encounterId\":\"E1\"}]}"));
        ReadEndpointClient client = new ReadEndpointClient(transport,
                "https://emr.test", "https://hai.test", "EMR_API_TOKEN", "HAI_API_TOKEN",
                new LocalReadCache(), "v1");
        JsonNode result = client.fetchEncounters("2026-01-01", "2026-03-31", null);
        assertEquals(1, result.path("count").asInt(), "缺字段记录仍保留并计数，不静默丢弃");
        assertTrue(client.incompleteRecordCount() >= 5);
    }
}
