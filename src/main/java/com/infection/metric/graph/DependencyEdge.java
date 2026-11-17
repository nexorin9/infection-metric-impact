package com.infection.metric.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;

/**
 * 依赖边：一条「口径依赖」的完整留痕记录。
 *
 * <p>边的方向语义：{@code source → target} 表示「source 依赖 target」，
 * 即 target 是被复用的口径（分母口径或另一条口径）。当 target 的内容发生变更时，
 * 依赖它的 source 及下游口径进入影响面。
 *
 * <p>字段：
 * <ul>
 *   <li>source / target：依赖关系的两端（口径条目 id，形如 {@code indicator_code|definition_version}）；</li>
 *   <li>fingerprint：被依赖口径的分母定义内容哈希（口径指纹），是这条边匹配的根据；</li>
 *   <li>evidence：人话依据，例如「复用同期出院患者病历总数分母」，供主任复核；</li>
 *   <li>created_at / created_by：建边时间与责任人；</li>
 *   <li>reviewRequired：被依赖口径内容变更后置为 {@code true}（待复核），
 *       需人工 {@code confirm} 后才继续计入影响面。</li>
 * </ul>
 *
 * <p>{@code edgeId} 由内容派生（source|target|fingerprint|evidence|created_at|created_by 的 sha256），
 * 稳定且可反查，作为 {@link EdgeEvidenceStore#confirm(String)} 的入参。
 */
public final class DependencyEdge {

    private final String source;
    private final String target;
    private final String fingerprint;
    private final String evidence;
    private final String createdAt;
    private final String createdBy;
    private final String edgeId;
    private boolean reviewRequired;

    /** 最小构造：时间与责任人留空，适用于图内部自动建的边（确定、可复现）。 */
    public DependencyEdge(String source, String target, String fingerprint, String evidence) {
        this(source, target, fingerprint, evidence, "", "");
    }

    public DependencyEdge(String source, String target, String fingerprint, String evidence,
                          String createdAt, String createdBy) {
        this.source = source == null ? "" : source;
        this.target = target == null ? "" : target;
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        this.evidence = evidence == null ? "" : evidence;
        this.createdAt = createdAt == null ? "" : createdAt;
        this.createdBy = createdBy == null ? "" : createdBy;
        this.edgeId = Hashes.sha256Of(this.source, this.target, this.fingerprint,
                this.evidence, this.createdAt, this.createdBy);
        this.reviewRequired = false;
    }

    public String getSource() { return source; }
    public String getTarget() { return target; }
    public String getFingerprint() { return fingerprint; }
    public String getEvidence() { return evidence; }
    public String getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public String getEdgeId() { return edgeId; }
    public boolean isReviewRequired() { return reviewRequired; }

    /** 标记为待复核（被依赖口径指纹变化后调用）。 */
    public void markReviewRequired() { this.reviewRequired = true; }

    /** 人工确认后清除待复核标记，边重新计入影响面。 */
    public void confirm() { this.reviewRequired = false; }

    public void setReviewRequired(boolean v) { this.reviewRequired = v; }

    /** 是否写了人话依据（库写入前校验用）。 */
    public boolean hasEvidence() { return evidence != null && !evidence.isBlank(); }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("source", source);
        o.put("target", target);
        o.put("fingerprint", fingerprint);
        o.put("evidence", evidence);
        o.put("created_at", createdAt);
        o.put("created_by", createdBy);
        o.put("edge_id", edgeId);
        o.put("review_required", reviewRequired);
        return o;
    }

    public static DependencyEdge fromJson(JsonNode n) {
        DependencyEdge e = new DependencyEdge(
                n.path("source").asText(""), n.path("target").asText(""),
                n.path("fingerprint").asText(""), n.path("evidence").asText(""),
                n.path("created_at").asText(""), n.path("created_by").asText(""));
        e.setReviewRequired(n.path("review_required").asBoolean(false));
        return e;
    }

    /** 规范化描述，供快照与差异比对使用（不含可变的复核标记）。 */
    public String canonical() { return source + "→" + target; }

    @Override
    public boolean equals(Object o) {
        return o instanceof DependencyEdge d && edgeId.equals(d.edgeId);
    }

    @Override
    public int hashCode() { return edgeId.hashCode(); }

    @Override
    public String toString() { return source + " → " + target + "（" + evidence + "）"; }
}
