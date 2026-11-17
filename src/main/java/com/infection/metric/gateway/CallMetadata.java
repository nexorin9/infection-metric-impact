package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

/**
 * 调用元数据骨架（任务11）+ 审计元数据（任务46）。
 *
 * <p>每次模型调用生成一条：模型名、提示版本、输入哈希、耗时；并补齐任务、schema 版本、
 * 指标编码、是否降级、错误码、重试次数、成功标志、发生时间，供
 * {@link CallAudit} 按 indicator_code 反查全部模型调用。</p>
 */
public final class CallMetadata {

    private final String indicatorCode;
    private final String task;
    private final String schemaVersion;
    private final String model;
    private final String promptVersion;
    private final String inputHash;
    private final long elapsedMillis;
    private final int retryCount;
    private final boolean success;
    private final boolean degraded;
    private final String errorCode;
    private final long occurredAtMillis;

    public CallMetadata(String indicatorCode, String task, String schemaVersion,
                        String model, String promptVersion, String inputHash,
                        long elapsedMillis, int retryCount, boolean success,
                        boolean degraded, String errorCode) {
        this.indicatorCode = indicatorCode;
        this.task = task;
        this.schemaVersion = schemaVersion;
        this.model = model;
        this.promptVersion = promptVersion;
        this.inputHash = inputHash;
        this.elapsedMillis = elapsedMillis;
        this.retryCount = retryCount;
        this.success = success;
        this.degraded = degraded;
        this.errorCode = errorCode;
        this.occurredAtMillis = System.currentTimeMillis();
    }

    public String getIndicatorCode() { return indicatorCode; }
    public String getTask() { return task; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public String getInputHash() { return inputHash; }
    public long getElapsedMillis() { return elapsedMillis; }
    public int getRetryCount() { return retryCount; }
    public boolean isSuccess() { return success; }
    public boolean isDegraded() { return degraded; }
    public String getErrorCode() { return errorCode; }
    public long getOccurredAtMillis() { return occurredAtMillis; }

    /** 审计记录的可序列化形态。 */
    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("indicator_code", indicatorCode);
        o.put("task", task);
        o.put("schema_version", schemaVersion);
        o.put("model", model);
        o.put("prompt_version", promptVersion);
        o.put("input_hash", inputHash);
        o.put("elapsed_millis", elapsedMillis);
        o.put("retry_count", retryCount);
        o.put("success", success);
        o.put("degraded", degraded);
        o.put("error_code", errorCode == null ? "" : errorCode);
        o.put("occurred_at_millis", occurredAtMillis);
        return o;
    }
}
