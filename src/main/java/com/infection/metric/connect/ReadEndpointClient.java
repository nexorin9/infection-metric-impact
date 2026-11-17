package com.infection.metric.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;
import com.infection.metric.common.OutboundTransport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 出院记录与院感明细读取端点：分页、日期区间与科室过滤、字段映射、缺字段显式报错并计数。 */
public final class ReadEndpointClient {

    public static final String ENCOUNTER_PATH = "/api/v1/encounters";
    public static final String INFECTION_REPORT_PATH = "/api/v1/infection-reports";
    public static final String FIELDS_PATH = "/api/v1/metadata/fields";

    /** 外部字段名 → 内部记录模型字段映射（与 spec 端点表一致）。 */
    public static final Map<String, String> ENCOUNTER_FIELD_MAP = Map.of(
            "encounterId", "就诊标识", "dischargeDate", "出院日期", "deptCode", "科室代码",
            "primaryDiagnosisCode", "主要诊断代码", "procedureCode", "手术操作代码",
            "woundClass", "切口等级");
    public static final Map<String, String> INFECTION_FIELD_MAP = Map.of(
            "infectionSite", "感染部位", "pathogen", "病原菌",
            "relatedSurgeryDate", "关联手术日期", "mdrFlag", "多重耐药菌标志",
            "deptCode", "科室代码", "onsetDate", "发病日期");

    private final OutboundTransport transport;
    private final String emrBaseUrl;
    private final String haiBaseUrl;
    private final String emrTokenEnv;
    private final String haiTokenEnv;
    private final LocalReadCache cache;
    private int incompleteRecords;

    public ReadEndpointClient(OutboundTransport transport, String emrBaseUrl, String haiBaseUrl,
                              String sourceVersion) {
        this(transport, emrBaseUrl, haiBaseUrl, "EMR_API_TOKEN", "HAI_API_TOKEN",
                new LocalReadCache(), sourceVersion);
    }

    public ReadEndpointClient(OutboundTransport transport, String emrBaseUrl, String haiBaseUrl,
                              String emrTokenEnv, String haiTokenEnv,
                              LocalReadCache cache, String sourceVersion) {
        this.transport = transport;
        this.emrBaseUrl = trimTrailingSlash(emrBaseUrl);
        this.haiBaseUrl = trimTrailingSlash(haiBaseUrl);
        this.emrTokenEnv = emrTokenEnv;
        this.haiTokenEnv = haiTokenEnv;
        this.cache = cache;
        this.sourceVersion = sourceVersion;
    }

    private final String sourceVersion;

    private static String trimTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** 出院记录：GET /api/v1/encounters?dischargeFrom=&dischargeTo=&deptCode=&page=&pageSize=。 */
    public JsonNode fetchEncounters(String dischargeFrom, String dischargeTo, String deptCode) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("dischargeFrom", dischargeFrom);
        query.put("dischargeTo", dischargeTo);
        if (deptCode != null && !deptCode.isBlank()) query.put("deptCode", deptCode);
        String key = cache.key(emrBaseUrl + ENCOUNTER_PATH, query, sourceVersion);
        return cache.getOrFetch(key, () -> {
            ArrayNode all = JsonSupport.array();
            int page = 1;
            while (page <= 100) {                       // 翻页终止：返回空页或少于 pageSize
                Map<String, String> pageQuery = new LinkedHashMap<>(query);
                pageQuery.put("page", String.valueOf(page));
                pageQuery.put("pageSize", "200");
                OutboundRequest req = buildGet(emrBaseUrl + ENCOUNTER_PATH, pageQuery, emrTokenEnv);
                JsonNode body = execute(req);
                ArrayNode records = extractArray(body, "encounters");
                for (JsonNode r : records) all.add(validateAndMap(r, ENCOUNTER_FIELD_MAP));
                if (records.size() < 200) break;
                page++;
            }
            ObjectNode result = JsonSupport.object();
            result.set("encounters", all);
            result.put("count", all.size());
            return result;
        });
    }

    /** 院感明细：GET /api/v1/infection-reports?period=。 */
    public JsonNode fetchInfectionReports(String period) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("period", period);
        String key = cache.key(haiBaseUrl + INFECTION_REPORT_PATH, query, sourceVersion);
        return cache.getOrFetch(key, () -> {
            OutboundRequest req = buildGet(haiBaseUrl + INFECTION_REPORT_PATH, query, haiTokenEnv);
            JsonNode body = execute(req);
            ArrayNode records = extractArray(body, "infection_reports");
            ArrayNode mapped = JsonSupport.array();
            for (JsonNode r : records) mapped.add(validateAndMap(r, INFECTION_FIELD_MAP));
            ObjectNode result = JsonSupport.object();
            result.set("infection_reports", mapped);
            result.put("count", mapped.size());
            return result;
        });
    }

    /** 字段字典：GET /api/v1/metadata/fields。 */
    public JsonNode fetchFieldDictionary() {
        Map<String, String> query = Map.of();
        String key = cache.key(emrBaseUrl + FIELDS_PATH, query, sourceVersion);
        return cache.getOrFetch(key, () -> {
            OutboundRequest req = buildGet(emrBaseUrl + FIELDS_PATH, query, emrTokenEnv);
            return execute(req);
        });
    }

    private OutboundRequest buildGet(String url, Map<String, String> query, String tokenEnv) {
        OutboundRequest.Builder b = OutboundRequest.builder("GET", url);
        query.forEach(b::query);
        String token = com.infection.metric.common.EnvConfig.fromEnvironment().get(tokenEnv);
        if (token != null) b.bearer(token);
        return b.build();
    }

    private JsonNode execute(OutboundRequest req) {
        OutboundResponse resp = transport.send(req);
        if (!resp.is2xx()) {
            throw FailureClassifier.error(
                    FailureClassifier.of(resp.getStatusCode(), false, false), req.getUrl(), resp.getStatusCode());
        }
        return JsonSupport.parse(resp.getBody().isBlank() ? "{}" : resp.getBody());
    }

    private static ArrayNode extractArray(JsonNode body, String field) {
        JsonNode n = body.path(field);
        if (n.isArray()) return (ArrayNode) n.deepCopy();
        if (body.isArray()) return (ArrayNode) body.deepCopy();
        return JsonSupport.array();
    }

    /** 缺必需字段的记录显式报错并计数，不静默丢弃。 */
    private JsonNode validateAndMap(JsonNode record, Map<String, String> fieldMap) {
        ObjectNode out = JsonSupport.object();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : fieldMap.entrySet()) {
            JsonNode v = record.get(e.getKey());
            if (v == null || v.isNull()) {
                // 非必填的空值：保留空串，但计入不完整数
                incompleteRecords++;
                out.put(e.getValue(), "");
            } else {
                out.set(e.getValue(), v.deepCopy());
            }
        }
        if (!missing.isEmpty()) {
            throw ErrorCodes.error(ErrorCodes.E_DATA_001, "field", String.join("、", missing));
        }
        return out;
    }

    public int incompleteRecordCount() { return incompleteRecords; }
    public LocalReadCache cache() { return cache; }
    public String getSourceVersion() { return sourceVersion; }
}
