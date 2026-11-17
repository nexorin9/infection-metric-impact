package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

/**
 * 单次复算结果（任务8）：按已发布口径对指定季度算出的分子与分母。
 *
 * <p><b>失败不静默改数据</b>：取数失败或字段缺失时状态为 {@link Status#FAILED}，
 * 分子 / 分母为 {@code null}（绝不是 0），并携带失败步骤与可读原因写入运行摘要。</p>
 */
public final class RecomputeResult {

    public enum Status { SUCCESS, FAILED }

    private final String indicatorCode;
    private final String definitionVersion;
    private final String quarter;
    private final Integer numerator;      // 失败时为 null，绝不按 0 计入分母
    private final Integer denominator;    // 失败时为 null
    private final Status status;
    private final String cacheKey;         // 口径规范化内容指纹 contentFingerprint()
    private final boolean cacheHit;
    private final String failedStep;
    private final String failureReason;
    private final long fetchMillis;
    private final long computeMillis;

    private RecomputeResult(String indicatorCode, String definitionVersion, String quarter,
                            Integer numerator, Integer denominator, Status status,
                            String cacheKey, boolean cacheHit, String failedStep,
                            String failureReason, long fetchMillis, long computeMillis) {
        this.indicatorCode = indicatorCode;
        this.definitionVersion = definitionVersion;
        this.quarter = quarter;
        this.numerator = numerator;
        this.denominator = denominator;
        this.status = status;
        this.cacheKey = cacheKey;
        this.cacheHit = cacheHit;
        this.failedStep = failedStep;
        this.failureReason = failureReason;
        this.fetchMillis = fetchMillis;
        this.computeMillis = computeMillis;
    }

    public static RecomputeResult success(String code, String version, String quarter,
                                         int numerator, int denominator, String cacheKey,
                                         long fetchMillis, long computeMillis) {
        return new RecomputeResult(code, version, quarter, numerator, denominator,
                Status.SUCCESS, cacheKey, false, null, null, fetchMillis, computeMillis);
    }

    public static RecomputeResult failure(String code, String version, String quarter,
                                         String cacheKey, String failedStep, String reason) {
        return new RecomputeResult(code, version, quarter, null, null,
                Status.FAILED, cacheKey, false, failedStep, reason, 0L, 0L);
    }

    /** 命中缓存时返回标记了 cacheHit 的副本（数字与输入逐字一致）。 */
    public RecomputeResult withCacheHit(boolean hit) {
        return new RecomputeResult(indicatorCode, definitionVersion, quarter,
                numerator, denominator, status, cacheKey, hit,
                failedStep, failureReason, fetchMillis, computeMillis);
    }

    public String getIndicatorCode() { return indicatorCode; }
    public String getDefinitionVersion() { return definitionVersion; }
    public String getQuarter() { return quarter; }
    public Integer getNumerator() { return numerator; }
    public Integer getDenominator() { return denominator; }
    public Status getStatus() { return status; }
    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isFailed() { return status == Status.FAILED; }
    public boolean hasValidNumbers() { return numerator != null && denominator != null; }
    public String getCacheKey() { return cacheKey; }
    public boolean isCacheHit() { return cacheHit; }
    public String getFailedStep() { return failedStep; }
    public String getFailureReason() { return failureReason; }
    public long getFetchMillis() { return fetchMillis; }
    public long getComputeMillis() { return computeMillis; }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("indicator_code", indicatorCode);
        o.put("definition_version", definitionVersion);
        o.put("quarter", quarter);
        if (numerator == null) o.putNull("numerator"); else o.put("numerator", numerator);
        if (denominator == null) o.putNull("denominator"); else o.put("denominator", denominator);
        o.put("status", status.name());
        o.put("cache_key", cacheKey);
        o.put("cache_hit", cacheHit);
        if (failedStep == null) o.putNull("failed_step"); else o.put("failed_step", failedStep);
        if (failureReason == null) o.putNull("failure_reason"); else o.put("failure_reason", failureReason);
        o.put("fetch_millis", fetchMillis);
        o.put("compute_millis", computeMillis);
        return o;
    }

    /** 从落盘 JSON 读回，支持「按年度分区列出并再次复算」。 */
    public static RecomputeResult fromJson(JsonNode n) {
        String code = n.path("indicator_code").asText("");
        String version = n.path("definition_version").asText("");
        String quarter = n.path("quarter").asText("");
        String cacheKey = n.path("cache_key").asText("");
        boolean failed = "FAILED".equals(n.path("status").asText("SUCCESS"));
        if (failed) {
            return failure(code, version, quarter, cacheKey,
                    n.path("failed_step").asText(""), n.path("failure_reason").asText(""));
        }
        return success(code, version, quarter,
                n.path("numerator").asInt(), n.path("denominator").asInt(), cacheKey,
                n.path("fetch_millis").asLong(), n.path("compute_millis").asLong());
    }
}
