package com.infection.metric.ui;

/**
 * 发布出站端口（函数接口）：把「写回院内质控系统」这一步注入给 UI 模型。
 *
 * <p>生产实现经真实出站端点（带幂等键）发布并返回回执；测试用内存替身返回固定回执或抛
 * AppException 模拟失败。业务层只依赖本接口，不耦合具体 HTTP 客户端。</p>
 */
@FunctionalInterface
public interface PublishPort {

    /** 执行一次发布；成功返回回执，失败抛 AppException（可读原因）。 */
    PublishReceipt publish(PublishRequest request);
}
