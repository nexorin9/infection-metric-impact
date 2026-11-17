package com.infection.metric.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 环境变量配置面。所有凭据与端点地址一律从环境变量读取，禁止硬编码、禁止写入日志。
 *
 * <p>此处只做「读得到 / 读不到」的判定与脱敏展示，不打印任何令牌明文。</p>
 */
public final class EnvConfig {

    /** 院内端点与模型服务的全部配置项（名称与 .env.example 一一对应）。 */
    public static final List<String> REQUIRED_KEYS = List.of(
            "EMR_BASE_URL",
            "EMR_API_TOKEN",
            "HAI_BASE_URL",
            "HAI_API_TOKEN",
            "QC_METRICS_BASE_URL",
            "QC_METRICS_TOKEN",
            "REGIONAL_PLATFORM_BASE_URL",
            "REGIONAL_TOKEN",
            "LLM_BASE_URL",
            "LLM_API_KEY",
            "LLM_MODEL",
            "LLM_TIMEOUT",
            "LLM_MAX_RETRIES");

    private final Map<String, String> values;

    private EnvConfig(Map<String, String> values) {
        this.values = values;
    }

    public static EnvConfig fromEnvironment() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : REQUIRED_KEYS) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                map.put(key, value.trim());
            }
        }
        return new EnvConfig(map);
    }

    public boolean isConfigured(String key) {
        return values.containsKey(key);
    }

    public String get(String key) {
        return values.get(key);
    }

    public String getOrDefault(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    public int getInt(String key, int fallback) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 未配置的配置项名称（用于自检报告）。 */
    public List<String> missing() {
        List<String> result = new ArrayList<>();
        for (String key : REQUIRED_KEYS) {
            if (!values.containsKey(key)) {
                result.add(key);
            }
        }
        return result;
    }

    /** 脱敏展示：令牌一律显示为「已配置（长度 N）」，绝不显示明文。 */
    public String describe(String key) {
        String value = values.get(key);
        if (value == null) {
            return "未配置";
        }
        if (key.endsWith("_TOKEN") || key.endsWith("_API_KEY")) {
            return "已配置（长度 " + value.length() + "）";
        }
        return value;
    }
}
