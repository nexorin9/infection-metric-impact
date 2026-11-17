package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

/**
 * 归因结论（任务34）：规则基线结论，或模型候选建议。
 *
 * <p>规则结论可信度恒为 1.0、无需人工确认；模型候选一律标注「待人工确认」并附
 * 置信度与依据引用，仅作建议、不自动采纳。</p>
 */
public final class Attribution {

    /** 结论来源通道。 */
    public enum Source {
        RULE("规则基线"),
        MODEL("模型候选（待人工确认）");

        private final String cn;
        Source(String cn) { this.cn = cn; }
        public String getCn() { return cn; }
    }

    private final BusinessDimension dimension;
    private final Source source;
    private final String detail;
    private final String evidenceRef;
    private final double confidence;
    private final boolean pendingHumanConfirmation;

    private Attribution(BusinessDimension dimension, Source source, String detail,
                        String evidenceRef, double confidence, boolean pending) {
        this.dimension = dimension;
        this.source = source;
        this.detail = detail;
        this.evidenceRef = evidenceRef;
        this.confidence = confidence;
        this.pendingHumanConfirmation = pending;
    }

    /** 规则基线结论：不依赖模型，可信度 1.0，无需人工确认。 */
    public static Attribution rule(BusinessDimension dimension, String detail, String evidenceRef) {
        return new Attribution(dimension, Source.RULE, detail, evidenceRef, 1.0, false);
    }

    /** 模型候选建议：待人工确认，置信度来自模型输出。 */
    public static Attribution model(BusinessDimension dimension, String detail,
                                    String evidenceRef, double confidence) {
        double c = Math.max(0.0, Math.min(1.0, confidence));
        return new Attribution(dimension, Source.MODEL, detail, evidenceRef, c, true);
    }

    public BusinessDimension getDimension() { return dimension; }
    public Source getSource() { return source; }
    public String getDetail() { return detail; }
    public String getEvidenceRef() { return evidenceRef; }
    public double getConfidence() { return confidence; }
    public boolean isPendingHumanConfirmation() { return pendingHumanConfirmation; }

    public String renderCn() {
        return "[" + source.getCn() + "] " + dimension.getCn() + "：" + detail
                + "（依据 " + evidenceRef + "，置信度 "
                + String.format("%.2f", confidence) + "）"
                + (pendingHumanConfirmation ? "【待人工确认】" : "");
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("dimension", dimension.name());
        o.put("dimension_cn", dimension.getCn());
        o.put("source", source.name());
        o.put("source_cn", source.getCn());
        o.put("detail", detail);
        o.put("evidence_ref", evidenceRef);
        o.put("confidence", confidence);
        o.put("pending_human_confirmation", pendingHumanConfirmation);
        return o;
    }
}
