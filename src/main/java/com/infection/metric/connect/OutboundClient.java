package com.infection.metric.connect;

import com.infection.metric.common.AppException;
import com.infection.metric.common.EnvConfig;
import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;
import com.infection.metric.common.OutboundTransport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 出站客户端内核：URL 拼装、Bearer 注入、错误四分类、指数退避重试。
 * 默认底层用真实 java.net.http.HttpClient（指向真实院内端点）；
 * 契约测试可注入内存 RawSender 替身断言请求形状，产品代码不改成只适配替身。
 */
public final class OutboundClient implements OutboundTransport {

    @FunctionalInterface
    public interface RawSender {
        /** 返回响应；连接失败/超时用 connectionFailed / timedOut 标记传出。 */
        RawResult send(OutboundRequest request, String fullUrl) throws IOException, InterruptedException;
    }

    public record RawResult(int status, String body, boolean connectionFailed, boolean timedOut) {
        public static RawResult ok(int status, String body) { return new RawResult(status, body, false, false); }
        public static RawResult connectionFailure() { return new RawResult(0, "", true, false); }
        public static RawResult timeout() { return new RawResult(0, "", false, true); }
    }

    private final RawSender sender;
    private final RetryPolicy retryPolicy;
    private final String defaultToken;

    public OutboundClient() { this(null, new RetryPolicy(), realHttpClient()); }
    public OutboundClient(RetryPolicy retryPolicy) { this(null, retryPolicy, realHttpClient()); }
    public OutboundClient(String defaultToken, RetryPolicy retryPolicy, RawSender sender) {
        this.defaultToken = defaultToken;
        this.retryPolicy = retryPolicy;
        this.sender = sender;
    }

    private static RawSender realHttpClient() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (request, fullUrl) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .timeout(Duration.ofSeconds(30));
            for (Map.Entry<String, String> h : request.getHeaders().entrySet()) {
                b.header(h.getKey(), h.getValue());
            }
            String method = request.getMethod();
            HttpRequest.BodyPublisher body = request.getBody().isBlank()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(request.getBody());
            b.method(method, body);
            try {
                HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                return RawResult.ok(resp.statusCode(), resp.body());
            } catch (java.net.http.HttpTimeoutException e) {
                return RawResult.timeout();
            }
        };
    }

    /** Bearer 注入：令牌只从环境变量读，不硬编码、不写日志。 */
    public OutboundRequest withBearer(OutboundRequest base, String envKey) {
        String token = EnvConfig.fromEnvironment().get(envKey);
        if (token == null && defaultToken != null) token = defaultToken;
        OutboundRequest.Builder b = OutboundRequest.builder(base.getMethod(), base.getUrl());
        base.getQuery().forEach(b::query);
        base.getHeaders().forEach(b::header);
        if (token != null) b.bearer(token);
        b.jsonBody(base.getBody());
        return b.build();
    }

    @Override
    public OutboundResponse send(OutboundRequest request) {
        String fullUrl = request.fullUrl();
        FailureClassifier.Kind lastKind = FailureClassifier.Kind.CONNECTION;
        int lastStatus = 0;
        String lastBody = "";
        long start = System.nanoTime();

        for (int attempt = 0; attempt <= retryPolicy.getMaxRetries(); attempt++) {
            RawResult raw;
            try {
                raw = sender.send(request, fullUrl);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                raw = RawResult.connectionFailure();
            }
            lastStatus = raw.status();
            lastBody = raw.body();
            lastKind = FailureClassifier.of(raw.status(), raw.connectionFailed(), raw.timedOut());
            if (lastKind == FailureClassifier.Kind.OK) {
                return new OutboundResponse(raw.status(), raw.body(),
                        (System.nanoTime() - start) / 1_000_000L, retryPolicy.retryCount());
            }
            if (!FailureClassifier.retryable(lastKind)) break;               // 4xx 不重试
            if (attempt < retryPolicy.getMaxRetries()) retryPolicy.waitBeforeRetry(attempt + 1);
        }
        throw FailureClassifier.error(lastKind, request.getUrl(), lastStatus)
                .with("body_excerpt", abbreviate(lastBody))
                .with("retry_count", String.valueOf(retryPolicy.retryCount()));
    }

    private static String abbreviate(String s) {
        if (s == null || s.isBlank()) return "(空)";
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    public RetryPolicy getRetryPolicy() { return retryPolicy; }
}
