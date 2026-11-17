package com.infection.metric.ui;

/** 发布回执：端点返回标识、时间戳、是否真有变更等，发布成功后当场显示并落入审计。 */
public final class PublishReceipt {

    private final String receiptId;
    private final String endpoint;
    private final boolean changed;
    private final int httpStatus;
    private final String timestamp;
    private final String rawBody;

    public PublishReceipt(String receiptId, String endpoint, boolean changed,
                          int httpStatus, String timestamp, String rawBody) {
        this.receiptId = receiptId;
        this.endpoint = endpoint;
        this.changed = changed;
        this.httpStatus = httpStatus;
        this.timestamp = timestamp;
        this.rawBody = rawBody;
    }

    public String getReceiptId() { return receiptId; }
    public String getEndpoint() { return endpoint; }
    public boolean isChanged() { return changed; }
    public int getHttpStatus() { return httpStatus; }
    public String getTimestamp() { return timestamp; }
    public String getRawBody() { return rawBody; }
}
