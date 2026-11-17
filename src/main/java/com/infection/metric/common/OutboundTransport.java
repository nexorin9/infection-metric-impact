package com.infection.metric.common;

/**
 * 出站传输接口：产品代码用真实 java.net.http.HttpClient 实现；
 * 契约测试用内存替身实现，断言请求形状。产品代码不得改成只适配替身。
 */
public interface OutboundTransport {
    OutboundResponse send(OutboundRequest request);
}
