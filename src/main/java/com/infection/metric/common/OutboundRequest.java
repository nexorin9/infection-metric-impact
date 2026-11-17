package com.infection.metric.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 出站请求快照：契约测试据此断言方法、路径、查询参数、请求头与请求体。 */
public final class OutboundRequest {
    private final String method;
    private final String url;
    private final Map<String, String> query;
    private final Map<String, String> headers;
    private final String body;

    public OutboundRequest(String method, String url, Map<String, String> query,
                           Map<String, String> headers, String body) {
        this.method = method;
        this.url = url;
        this.query = query == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(query));
        this.headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? "" : body;
    }

    public String getMethod() { return method; }
    public String getUrl() { return url; }
    public Map<String, String> getQuery() { return query; }
    public Map<String, String> getHeaders() { return headers; }
    public String getBody() { return body; }

    /** 拼好查询参数后的完整 URL（参数做 URL 编码）。 */
    public String fullUrl() {
        if (query.isEmpty()) return url;
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!first) sb.append('&');
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    public static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Builder builder(String method, String url) { return new Builder(method, url); }

    public static final class Builder {
        private final String method, url;
        private final Map<String, String> query = new LinkedHashMap<>();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String body = "";
        private Builder(String method, String url) { this.method = method; this.url = url; }
        public Builder query(String k, String v) { query.put(k, v); return this; }
        public Builder header(String k, String v) { headers.put(k, v); return this; }
        public Builder bearer(String token) { headers.put("Authorization", "Bearer " + token); return this; }
        public Builder idempotencyKey(String key) { headers.put("Idempotency-Key", key); return this; }
        public Builder jsonBody(String json) {
            headers.put("Content-Type", "application/json");
            this.body = json;
            return this;
        }
        public OutboundRequest build() { return new OutboundRequest(method, url, query, headers, body); }
    }
}
