package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.common.ModelTaskPort;
import com.infection.metric.metriclib.MetricDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 差异业务归因层（任务34）：规则基线 + 模型候选。
 *
 * <p>规则通道按差异条件所在的树位置（分子树 / 分母树 / 排除树）与 scope 字段映射到
 * 业务维度，<b>完全不依赖模型即可跑通</b>；模型候选通道经
 * {@link ModelTaskPort#call(String, String, JsonNode)} 的 {@code attribute_diff} 任务给出
 * 候选维度与依据引用，仅作建议、标注「待人工确认」并附置信度。</p>
 *
 * <p>两通道合并时规则结论优先：规则结论排在前面且可信度为 1.0；模型候选即便命中
 * 同一维度也只作为补充建议，绝不覆盖规则结论。模型端口为 null、抛错或返回 null
 * 时自动降级为纯规则结论，归因仍可用、不中断业务。</p>
 */
public final class AttributionEngine {

    public static final String TASK = "attribute_diff";
    public static final String DEFAULT_SCHEMA_VERSION = "1.0";

    private final ModelTaskPort modelPort;
    private final String schemaVersion;
    private final DiffEngine diffEngine = new DiffEngine();

    /** 无模型：纯规则基线归因（规则通道独立可跑）。 */
    public AttributionEngine() {
        this(null, DEFAULT_SCHEMA_VERSION);
    }

    public AttributionEngine(ModelTaskPort modelPort) {
        this(modelPort, DEFAULT_SCHEMA_VERSION);
    }

    public AttributionEngine(ModelTaskPort modelPort, String schemaVersion) {
        this.modelPort = modelPort;
        this.schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? DEFAULT_SCHEMA_VERSION : schemaVersion;
    }

    /** 归因入口（结构化差异结果）：规则结论优先，模型候选附后。 */
    public List<Attribution> attribute(DiffResult diff) {
        List<Attribution> out = new ArrayList<>();
        out.addAll(ruleBaseline(diff));      // 规则结论优先
        out.addAll(modelCandidates(diff));  // 模型候选仅作建议
        return out;
    }

    /** 归因入口（两版口径）：先做结构化差异，再归因。 */
    public List<Attribution> attribute(MetricDefinition oldDef, MetricDefinition newDef) {
        return attribute(diffEngine.diff(oldDef, newDef));
    }

    /** 规则基线归因：按差异条件所在位置与 scope 字段映射维度，同维度合并去重。 */
    public List<Attribution> ruleBaseline(DiffResult diff) {
        Map<BusinessDimension, List<String>> grouped = new LinkedHashMap<>();
        if (diff != null) {
            for (ConditionChange c : diff.all()) {
                BusinessDimension d = map(c.getPosition());
                grouped.computeIfAbsent(d, k -> new ArrayList<>()).add(c.getField());
            }
        }
        List<Attribution> out = new ArrayList<>();
        for (Map.Entry<BusinessDimension, List<String>> e : grouped.entrySet()) {
            BusinessDimension dim = e.getKey();
            Set<String> fields = new LinkedHashSet<>(e.getValue());
            String detail = dim.getCn() + "发生变更，涉及条件字段：" + String.join("、", fields);
            out.add(Attribution.rule(dim, detail, "rule:" + dim.name()));
        }
        return out;
    }

    private static BusinessDimension map(TreePosition position) {
        return switch (position) {
            case NUMERATOR -> BusinessDimension.NUMERATOR_SCOPE;
            case DENOMINATOR -> BusinessDimension.DENOMINATOR_DEFINITION;
            case EXCLUSION -> BusinessDimension.EXCLUSION_CONDITION;
            case SCOPE_DEPT -> BusinessDimension.DEPT_SCOPE;
            case SCOPE_PERIOD, EFFECTIVE_FROM, EFFECTIVE_TO -> BusinessDimension.EFFECTIVE_PERIOD;
        };
    }

    // ---------------------------------------------------------------- 模型候选通道

    private List<Attribution> modelCandidates(DiffResult diff) {
        if (modelPort == null || diff == null || diff.isEmpty()) {
            return List.of();
        }
        JsonNode response;
        try {
            response = modelPort.call(TASK, schemaVersion, buildPayload(diff));
        } catch (RuntimeException e) {
            return List.of(); // 模型不可用 → 降级，不影响规则结论
        }
        if (response == null || !response.isArray()) {
            return List.of();
        }
        List<Attribution> out = new ArrayList<>();
        for (JsonNode item : response) {
            // 本地 schema 校验：非法维度、空依据引用一律丢弃，绝不猜测修复
            BusinessDimension dim = BusinessDimension.of(item.path("dimension").asText(""));
            String evidenceRef = item.path("evidence_ref").asText("");
            if (dim == null || evidenceRef.isBlank()) continue;
            String detail = item.path("detail").asText("");
            double confidence = item.path("confidence").isNumber()
                    ? item.path("confidence").asDouble() : 0.5;
            out.add(Attribution.model(dim, detail, evidenceRef, confidence));
        }
        return out;
    }

    private ObjectNode buildPayload(DiffResult diff) {
        ObjectNode payload = JsonSupport.object();
        ArrayNode entries = payload.putArray("diff_entries");
        for (ConditionChange c : diff.all()) {
            entries.add(c.toJson());
        }
        ArrayNode dims = payload.putArray("dimensions");
        for (BusinessDimension d : BusinessDimension.values()) {
            dims.add(d.name() + "|" + d.getCn());
        }
        return payload;
    }
}
