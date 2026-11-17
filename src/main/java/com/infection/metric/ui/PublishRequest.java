package com.infection.metric.ui;

/** 一次发布请求：指标编码 + 口径版本 + 幂等键 + 发布前状态。 */
public final class PublishRequest {

    private final String indicatorCode;
    private final String definitionVersion;
    private final String idempotencyKey;
    private final String status;

    public PublishRequest(String indicatorCode, String definitionVersion,
                          String idempotencyKey, String status) {
        this.indicatorCode = indicatorCode;
        this.definitionVersion = definitionVersion;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
    }

    public String getIndicatorCode() { return indicatorCode; }
    public String getDefinitionVersion() { return definitionVersion; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getStatus() { return status; }

    /** 联合主键 = indicator_code|definition_version。 */
    public String getKey() { return indicatorCode + "|" + definitionVersion; }
}
