package com.infection.metric.common;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 单一模型网关调用口。业务层只依赖这个接口，不耦合任何厂商 SDK。
 * 规则通道不依赖它即可跑通；模型不可用时返回降级结果而不推进业务状态。
 */
public interface ModelTaskPort {
    JsonNode call(String task, String schemaVersion, JsonNode payload);
}
