package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;

/**
 * 本地 schema 校验基础（任务11）与三类任务契约共用的校验原语。
 *
 * <p>所有不合格输出一律抛 E_LLM_004（schema 校验失败），不猜测修复、不重试。
 * 失败文案统一走 {@link ErrorCodes#error}，具体原因放进上下文 detail。</p>
 */
public final class ContractSchemas {

    private ContractSchemas() {}

    /** 生成带可读原因的 E_LLM_004。 */
    public static AppException fail(String reason) {
        return ErrorCodes.error(ErrorCodes.E_LLM_004, "detail", reason);
    }

    public static void requireContainer(JsonNode node, String what) {
        if (node == null || !node.isContainerNode()) {
            throw fail(what + " 应为 JSON 对象或数组");
        }
    }

    public static void requireObject(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw fail(what + " 应为 JSON 对象");
        }
    }

    public static JsonNode requireArrayField(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || !n.isArray()) {
            throw fail("字段 " + field + " 缺失或类型不符（应为数组）");
        }
        return n;
    }

    public static JsonNode requireObjectField(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || !n.isObject()) {
            throw fail("字段 " + field + " 缺失或类型不符（应为对象）");
        }
        return n;
    }

    public static String requireNonBlankText(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw fail("字段 " + field + " 缺失、类型不符或为空");
        }
        return n.asText();
    }
}
