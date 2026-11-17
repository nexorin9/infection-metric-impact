package com.infection.metric.gateway;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;
import com.infection.metric.common.OutboundTransport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 真实出站传输实现（任务11）：基于 {@link java.net.http.HttpClient} 的 OpenAI-compatible 发送器。
 *
 * <p>产品代码默认使用该实现；契约测试注入内存替身，产品代码不得改成只适配替身。
 * 超时取 LLM_TIMEOUT；连接失败 / 超时分别归类为 E_LLM_001 / E_LLM_002，交给网关重试。</p>
 */
public final class HttpTransport implements OutboundTransport {

    private final long timeoutMillis;
    private final HttpClient client;

    public HttpTransport(GatewayConfig config) {
        this(config.getTimeoutMillis());
    }

    public HttpTransport(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis
                : (long) GatewayConfig.DEFAULT_TIMEOUT_SECONDS * 1000L;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMillis))
                .build();
    }

    public long getTimeoutMillis() { return timeoutMillis; }

    @Override
    public OutboundResponse send(OutboundRequest request) {
        long start = System.nanoTime();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(request.fullUrl()))
                    .timeout(Duration.ofMillis(timeoutMillis));
            for (var e : request.getHeaders().entrySet()) {
                b.header(e.getKey(), e.getValue());
            }
            HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers
                    .ofString(request.getBody(), StandardCharsets.UTF_8);
            switch (request.getMethod()) {
                case "POST" -> b.POST(body);
                case "PUT" -> b.PUT(body);
                case "GET" -> b.GET();
                default -> b.method(request.getMethod(), body);
            }
            HttpResponse<String> resp = client.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                // 非 2xx 视为服务侧不可用，按 E_LLM_001 交给网关重试
                throw ErrorCodes.error(ErrorCodes.E_LLM_001,
                        "status", String.valueOf(resp.statusCode()));
            }
            return new OutboundResponse(resp.statusCode(), resp.body(), elapsed, 0);
        } catch (AppException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw ErrorCodes.error(ErrorCodes.E_LLM_002);
        } catch (IOException e) {
            throw new AppException(ErrorCodes.E_LLM_001,
                    ErrorCodes.text(ErrorCodes.E_LLM_001), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(ErrorCodes.E_LLM_001,
                    ErrorCodes.text(ErrorCodes.E_LLM_001), e);
        }
    }
}
