package com.infection.metric.ui;

import com.infection.metric.metriclib.MetricStatus;

/** 一次发布动作的结果：成功带回执，失败带可读原因与运行摘要入口，且记录发布后状态。 */
public final class PublishOutcome {

    private final boolean success;
    private final PublishReceipt receipt;
    private final String failureReason;
    private final String summaryEntry;
    private final MetricStatus status;

    private PublishOutcome(boolean success, PublishReceipt receipt, String failureReason,
                           String summaryEntry, MetricStatus status) {
        this.success = success;
        this.receipt = receipt;
        this.failureReason = failureReason;
        this.summaryEntry = summaryEntry;
        this.status = status;
    }

    public static PublishOutcome success(PublishReceipt receipt, MetricStatus status) {
        return new PublishOutcome(true, receipt, "", "", status);
    }

    public static PublishOutcome failure(String failureReason, String summaryEntry, MetricStatus status) {
        return new PublishOutcome(false, null, failureReason, summaryEntry, status);
    }

    public boolean isSuccess() { return success; }
    public PublishReceipt getReceipt() { return receipt; }
    public String getFailureReason() { return failureReason; }
    public String getSummaryEntry() { return summaryEntry; }
    public MetricStatus getStatus() { return status; }
}
