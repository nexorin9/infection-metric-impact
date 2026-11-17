package com.infection.metric.gateway;

import com.infection.metric.common.EnvConfig;

import java.net.URI;

/**
 * 模型网关配置面（任务11）：五项配置全部来自 {@link EnvConfig} / 环境变量。
 *
 * <p>业务层不引用任何厂商 SDK；本类只负责把 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL /
 * LLM_TIMEOUT / LLM_MAX_RETRIES 读成强类型值，并提供 OpenAI-compatible 端点拼装。</p>
 *
 * <p>LLM_TIMEOUT 默认 30 秒，LLM_MAX_RETRIES 默认 2（即除首次外最多再重试 2 次）。
 * 提示版本（prompt version）不来自环境变量，作为本网关协议版本常量，写入调用审计元数据。</p>
 *
 * <p>端点拼装规则：base_url 若只含「协议 + 主机」（路径为空），自动拼接
 * OpenAI-compatible 默认端点 {@code /v1/chat/completions}；若 base_url 已含自定义路径，
 * 则整体作为端点使用——替换 base_url 即可切换服务方、支持自定义端点路径。</p>
 */
public final class GatewayConfig {

    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final String DEFAULT_ENDPOINT_PATH = "/v1/chat/completions";
    public static final String DEFAULT_PROMPT_VERSION = "1.0";

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final int maxRetries;
    private final String promptVersion;

    private GatewayConfig(String baseUrl, String apiKey, String model,
                          int timeoutSeconds, int maxRetries, String promptVersion) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.maxRetries = maxRetries >= 0 ? maxRetries : DEFAULT_MAX_RETRIES;
        this.promptVersion = promptVersion == null || promptVersion.isBlank()
                ? DEFAULT_PROMPT_VERSION : promptVersion;
    }

    /** 生产入口：从真实环境变量读取（缺省项取协议默认值）。 */
    public static GatewayConfig fromEnvironment() {
        return fromEnv(EnvConfig.fromEnvironment());
    }

    /** 从给定 EnvConfig 读取，便于注入与自检。 */
    public static GatewayConfig fromEnv(EnvConfig env) {
        return new GatewayConfig(
                env.getOrDefault("LLM_BASE_URL", ""),
                env.getOrDefault("LLM_API_KEY", ""),
                env.getOrDefault("LLM_MODEL", ""),
                env.getInt("LLM_TIMEOUT", DEFAULT_TIMEOUT_SECONDS),
                env.getInt("LLM_MAX_RETRIES", DEFAULT_MAX_RETRIES),
                DEFAULT_PROMPT_VERSION);
    }

    /** 测试 / 程序化注入入口：显式给定五项配置，不经环境变量。 */
    public static GatewayConfig of(String baseUrl, String apiKey, String model,
                                   int timeoutSeconds, int maxRetries) {
        return new GatewayConfig(baseUrl, apiKey, model, timeoutSeconds, maxRetries,
                DEFAULT_PROMPT_VERSION);
    }

    /** 使用默认超时与重试上限的简化注入入口。 */
    public static GatewayConfig of(String baseUrl, String apiKey, String model) {
        return of(baseUrl, apiKey, model, DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_RETRIES);
    }

    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public String getPromptVersion() { return promptVersion; }
    public long getTimeoutMillis() { return (long) timeoutSeconds * 1000L; }

    /** 拼接后的 POST 端点 URL；base_url 未配置时返回空串（调用方按连接失败处理）。 */
    public String endpointUrl() {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            return "";
        }
        String path = null;
        try {
            path = URI.create(base).getPath();
        } catch (IllegalArgumentException ignored) {
            // 非法 URI 交由下层按连接失败处理，这里只做保守拼接
        }
        boolean hasCustomPath = path != null && !path.isEmpty() && !"/".equals(path);
        return hasCustomPath ? base : base + DEFAULT_ENDPOINT_PATH;
    }
}
