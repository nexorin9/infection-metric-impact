package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.metriclib.MetricStatus;

/**
 * 一次「可降级」模型调用的结果（任务46）。
 *
 * <p>成功时口径来源 = 模型，结果仍是草稿；模型不可达时口径来源 = 人工，
 * 退回人工录入条件树 + 规则校验，发布状态原样保留（DRAFT / PENDING_REVIEW），
 * 绝不自动推进到 PUBLISHED。模型输出永远是草稿。</p>
 */
public final class CallOutcome {

    private final boolean degraded;
    private final boolean success;
    private final String source;
    private final String sourceLabel;
    private final MetricStatus status;
    private final JsonNode payload;
    private final String errorCode;
    private final boolean modelOutputIsDraft;
    private final boolean manualInputRequired;
    private final String note;

    private CallOutcome(boolean degraded, boolean success, String source, String sourceLabel,
                        MetricStatus status, JsonNode payload, String errorCode,
                        boolean modelOutputIsDraft, boolean manualInputRequired, String note) {
        this.degraded = degraded;
        this.success = success;
        this.source = source;
        this.sourceLabel = sourceLabel;
        this.status = status;
        this.payload = payload;
        this.errorCode = errorCode;
        this.modelOutputIsDraft = modelOutputIsDraft;
        this.manualInputRequired = manualInputRequired;
        this.note = note;
    }

    /** 模型成功返回：来源「模型」、结果草稿、状态不变、未降级。 */
    public static CallOutcome modelSuccess(JsonNode payload, MetricStatus status) {
        return new CallOutcome(false, true, "模型", "口径来源 = 模型",
                status, payload, null, true, false,
                "模型输出仅为草稿，状态由人推进");
    }

    /** 模型不可达降级：来源「人工」、状态不变、需人工录入条件树 + 规则校验。 */
    public static CallOutcome degraded(MetricStatus status, String errorCode) {
        return new CallOutcome(true, false, "人工", "口径来源 = 人工",
                status, null, errorCode, true, true,
                "模型不可达，退回人工录入条件树 + 规则校验；发布状态不自动推进");
    }

    public boolean isDegraded() { return degraded; }
    public boolean isSuccess() { return success; }
    public String getSource() { return source; }
    public String getSourceLabel() { return sourceLabel; }
    public MetricStatus getStatus() { return status; }
    public JsonNode getPayload() { return payload; }
    public String getErrorCode() { return errorCode; }
    public boolean isModelOutputIsDraft() { return modelOutputIsDraft; }
    public boolean isManualInputRequired() { return manualInputRequired; }
    public String getNote() { return note; }
}
