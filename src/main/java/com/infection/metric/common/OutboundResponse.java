package com.infection.metric.common;

/** 出站响应快照。 */
public final class OutboundResponse {
    private final int statusCode;
    private final String body;
    private final long elapsedMillis;
    private final int retryCount;

    public OutboundResponse(int statusCode, String body, long elapsedMillis, int retryCount) {
        this.statusCode = statusCode;
        this.body = body == null ? "" : body;
        this.elapsedMillis = elapsedMillis;
        this.retryCount = retryCount;
    }

    public static OutboundResponse of(int status, String body) {
        return new OutboundResponse(status, body, 0L, 0);
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
    public long getElapsedMillis() { return elapsedMillis; }
    public int getRetryCount() { return retryCount; }
    public boolean is2xx() { return statusCode >= 200 && statusCode < 300; }
}
