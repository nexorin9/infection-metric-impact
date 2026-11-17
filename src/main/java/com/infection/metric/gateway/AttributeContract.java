package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.recompute.Attribution;
import com.infection.metric.recompute.BusinessDimension;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型任务契约（任务45）：两版差异业务归因，task = attribute_diff。
 *
 * <p>请求 payload 含两版 canonical form 的差异条目与业务维度枚举；响应契约固定为
 * {@code [{dimension, detail, evidence_ref, affected_periods[]}]}：dimension 必须是
 * {@link BusinessDimension} 约定枚举之一，evidence_ref 必须指向具体差异条目，
 * affected_periods 非空。非法维度、空 evidence_ref、空 affected_periods 一律判 E_LLM_004。</p>
 *
 * <p>与规则通道合并时规则结论优先；模型结论统一标「待人工确认」并附置信度与依据引用，
 * 仅作建议、不自动采纳（复用 recompute 包 {@link Attribution} 的结论模型）。</p>
 */
public final class AttributeContract implements TaskContract {

    public static final String TASK = "attribute_diff";
    public static final String SCHEMA_VERSION = "1.0";

    @Override
    public String task() { return TASK; }

    @Override
    public String schemaVersion() { return SCHEMA_VERSION; }

    /** 构造合规请求 payload：差异条目（带 id）+ 业务维度枚举。 */
    public static ObjectNode buildPayload(String indicatorCode, List<String> diffEntryIds) {
        ObjectNode payload = JsonSupport.object();
        payload.put("indicator_code", indicatorCode);
        ArrayNode entries = payload.putArray("diff_entries");
        if (diffEntryIds != null) {
            for (String id : diffEntryIds) {
                ObjectNode e = entries.addObject();
                e.put("id", id);
                e.put("field", id);
                e.put("dimension", "NUMERATOR_SCOPE");
            }
        }
        ArrayNode dims = payload.putArray("dimensions");
        for (BusinessDimension d : BusinessDimension.values()) {
            dims.add(d.name());
        }
        return payload;
    }

    /** 构造一份合规的归因响应。 */
    public static ArrayNode sampleResponse(String dimension, String evidenceRef) {
        ArrayNode arr = JsonSupport.array();
        ObjectNode o = arr.addObject();
        o.put("dimension", dimension);
        o.put("detail", "分子范围收紧，受影响季度需重算");
        o.put("evidence_ref", evidenceRef);
        o.put("confidence", 0.74);
        o.putArray("affected_periods").add("2026Q1").add("2026Q2");
        return arr;
    }

    @Override
    public void validateResponse(JsonNode response, JsonNode request) {
        ContractSchemas.requireContainer(response, "attribute_diff 响应");
        if (!response.isArray()) {
            throw ContractSchemas.fail("attribute_diff 响应应为数组");
        }
        Set<String> knownRefs = diffEntryRefs(request);
        int i = 0;
        for (JsonNode item : response) {
            String where = "归因[" + i + "]";
            ContractSchemas.requireObject(item, where);
            String dimension = ContractSchemas.requireNonBlankText(item, "dimension");
            if (BusinessDimension.of(dimension) == null) {
                throw ContractSchemas.fail(where + " 非法业务维度：" + dimension);
            }
            String evidenceRef = ContractSchemas.requireNonBlankText(item, "evidence_ref");
            if (!knownRefs.isEmpty() && !knownRefs.contains(evidenceRef)) {
                throw ContractSchemas.fail(where + " evidence_ref 未指向具体差异条目：" + evidenceRef);
            }
            JsonNode periods = ContractSchemas.requireArrayField(item, "affected_periods");
            if (periods.isEmpty()) {
                throw ContractSchemas.fail(where + " affected_periods 为空");
            }
            i++;
        }
    }

    /** 从请求差异条目收集可被 evidence_ref 指向的引用（id / field / ref）。 */
    private static Set<String> diffEntryRefs(JsonNode request) {
        Set<String> refs = new LinkedHashSet<>();
        if (request != null && request.path("diff_entries").isArray()) {
            for (JsonNode e : request.path("diff_entries")) {
                for (String key : new String[]{"id", "field", "ref", "evidence_ref"}) {
                    String v = e.path(key).asText("");
                    if (!v.isBlank()) refs.add(v);
                }
            }
        }
        return refs;
    }

    /**
     * 与规则通道合并：规则结论优先（排在前面），模型结论标「待人工确认」并附置信度与依据引用。
     * 模型候选即便命中同一维度也只作补充建议，绝不覆盖规则结论。
     */
    public static List<Attribution> merge(List<Attribution> ruleConclusions, JsonNode modelResponse) {
        List<Attribution> out = new ArrayList<>();
        if (ruleConclusions != null) out.addAll(ruleConclusions);
        if (modelResponse != null && modelResponse.isArray()) {
            for (JsonNode item : modelResponse) {
                BusinessDimension dim = BusinessDimension.of(item.path("dimension").asText(""));
                if (dim == null) continue; // 非法维度已由 validateResponse 拦截
                double confidence = item.path("confidence").isNumber()
                        ? item.path("confidence").asDouble() : 0.5;
                out.add(Attribution.model(dim,
                        item.path("detail").asText(""),
                        item.path("evidence_ref").asText(""),
                        Math.max(0.0, Math.min(1.0, confidence))));
            }
        }
        return out;
    }
}
