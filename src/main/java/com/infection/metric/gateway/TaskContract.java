package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;

/**
 * 模型任务契约（任务43/44/45 的统一抽象）。
 *
 * <p>网关按 task 名分派到具体契约，对每个任务的「响应」做本地 schema 校验；
 * 不合格即抛 {@link AppException}（E_LLM_004），不猜测修复、不重试。</p>
 *
 * <p>校验同时拿到请求 payload，以便做引用存在性校验：别名候选对必须引用真实候选 id、
 * 归因的 evidence_ref 必须指向具体差异条目、维度必须落在约定枚举内。</p>
 */
public interface TaskContract {

    /** 任务名，同时作为网关分派键。 */
    String task();

    /** 该契约的 schema 版本。 */
    String schemaVersion();

    /**
     * 校验响应契约。
     *
     * @param responsePayload 解包后的模型响应
     * @param requestPayload  本次发送的请求 payload（提供候选 id / 差异条目等上下文）
     * @throws AppException E_LLM_004，任何字段缺失、类型不符、引用为空或越界
     */
    void validateResponse(JsonNode responsePayload, JsonNode requestPayload);
}
