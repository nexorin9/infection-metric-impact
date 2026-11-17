package com.infection.metric.graph;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 影响面分析结果：受影响指标（带深度）+ 需重算历史季度 + 错误/说明文案。
 *
 * <p>当依赖图成环或存在悬空边时，{@link ImpactAnalyzer} 不抛异常中断，
 * 而是返回「受影响为空 + {@code errors} 可读文案」的结果，保证影响面为空且错误可读。
 */
public final class ImpactAnalysisResult {

    /** 一行受影响指标。 */
    public static final class ImpactRow {
        private final String nodeId;
        private final String code;
        private final String name;
        private final String version;
        private final int depth;
        private final String evidence;
        private final String explanation;
        private final String source;

        public ImpactRow(String nodeId, String code, String name, String version,
                         int depth, String evidence, String explanation, String source) {
            this.nodeId = nodeId;
            this.code = code;
            this.name = name;
            this.version = version;
            this.depth = depth;
            this.evidence = evidence;
            this.explanation = explanation;
            this.source = source;
        }

        public String getNodeId() { return nodeId; }
        public String getCode() { return code; }
        public String getName() { return name; }
        public String getVersion() { return version; }
        public int getDepth() { return depth; }
        public String getEvidence() { return evidence; }
        public String getExplanation() { return explanation; }
        public String getSource() { return source; }

        public ObjectNode toJson() {
            ObjectNode o = JsonSupport.object();
            o.put("indicator_code", code);
            o.put("name", name);
            o.put("definition_version", version);
            o.put("depth", depth);
            o.put("evidence", evidence);
            o.put("explanation", explanation);
            o.put("source", source);
            return o;
        }
    }

    private final String changedId;
    private final List<ImpactRow> affected;
    private final List<String> recomputeQuarters;
    private final List<String> errors;
    private final String note;

    public ImpactAnalysisResult(String changedId, List<ImpactRow> affected,
                                List<String> recomputeQuarters, List<String> errors, String note) {
        this.changedId = changedId;
        this.affected = List.copyOf(affected);
        this.recomputeQuarters = List.copyOf(recomputeQuarters);
        this.errors = List.copyOf(errors);
        this.note = note == null ? "" : note;
    }

    /** 构建失败（成环/悬空）：影响面为空，错误文案可读。 */
    public static ImpactAnalysisResult emptyWithErrors(String changedId, List<String> errors) {
        return new ImpactAnalysisResult(changedId, List.of(), List.of(),
                errors, "依赖图构建失败，影响面为空");
    }

    /** 未知变更口径：影响面为空，给出可读原因。 */
    public static ImpactAnalysisResult unknown(String changedId, String reason) {
        return new ImpactAnalysisResult(changedId, List.of(), List.of(),
                List.of(reason), "未找到变更口径，影响面为空");
    }

    public String getChangedId() { return changedId; }
    public List<ImpactRow> getAffected() { return affected; }
    public List<String> getRecomputeQuarters() { return recomputeQuarters; }
    public List<String> getErrors() { return errors; }
    public String getNote() { return note; }

    public boolean isEmpty() { return affected.isEmpty(); }
    public boolean hasErrors() { return !errors.isEmpty(); }

    /** 需重算季度的人话文本：无交集时明确写「无历史季度需重算」，不留空。 */
    public String recomputeQuartersText() {
        return recomputeQuarters.isEmpty() ? "无历史季度需重算"
                : String.join("，", recomputeQuarters);
    }

    /** 结果里出现过的深度集合，供「默认非递归」断言。 */
    public Set<Integer> depths() {
        Set<Integer> s = new LinkedHashSet<>();
        for (ImpactRow r : affected) s.add(r.getDepth());
        return Collections.unmodifiableSet(s);
    }

    public List<String> affectedCodes() {
        List<String> out = new ArrayList<>();
        for (ImpactRow r : affected) out.add(r.getCode());
        return out;
    }

    public String renderErrors() { return String.join("\n", errors); }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("changed_id", changedId);
        ArrayNode rows = o.putArray("affected_indicators");
        for (ImpactRow r : affected) rows.add(r.toJson());
        ArrayNode qs = o.putArray("recompute_quarters");
        for (String q : recomputeQuarters) qs.add(q);
        o.put("recompute_quarters_text", recomputeQuartersText());
        ArrayNode es = o.putArray("errors");
        for (String e : errors) es.add(e);
        o.put("note", note);
        o.put("affected_count", affected.size());
        return o;
    }
}
