package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.common.ModelTaskPort;
import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;
import com.infection.metric.common.OutboundTransport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 唯一模型网关（任务11 内核 + 任务46 错误码/重试/审计），实现 {@link ModelTaskPort}。
 *
 * <p>业务层只依赖本网关，不引用任何厂商 SDK。配置全部来自 {@link GatewayConfig}（环境变量）。
 * 结构化请求体固定为 {@code {task, schema_version, payload}}；Content-Type 与
 * Authorization: Bearer 按 OpenAI-compatible 协议组装，模型名经 X-LLM-Model 头传递，
 * base_url 可含自定义端点路径，替换即可切换服务方。</p>
 *
 * <p>响应解析同时支持「直接返回契约对象/数组」与「OpenAI-compatible choices 信封」，
 * 产品代码不只为某个替身定制。响应必须是合法 JSON 容器并通过本地 schema 校验，
 * 不合格即失败、不猜测修复；schema 校验失败不重试（同输入必然同结果）。</p>
 *
 * <p>五类错误：E_LLM_001 连接失败 / 002 超时 / 003 响应非 JSON / 004 schema 校验失败 /
 * 005 空结果。连接失败与超时按 LLM_MAX_RETRIES 重试（超时取 LLM_TIMEOUT），
 * 其余三类直接失败；每次调用都写 {@link CallAudit}，失败一律抛 AppException、不吞异常。</p>
 *
 * <p>可注入 {@link OutboundTransport}：默认真实 {@link HttpTransport}，测试用本地替身。</p>
 */
public final class ModelGateway implements ModelTaskPort {

    /** 默认提示版本（协议版本），写入调用审计元数据。 */
    public static final String PROMPT_VERSION = GatewayConfig.DEFAULT_PROMPT_VERSION;

    private final GatewayConfig config;
    private final OutboundTransport transport;
    private final CallAudit audit;
    private final Map<String, TaskContract> contracts;

    /** 生产默认：环境变量配置 + 真实 HttpClient 传输 + 三类任务契约。 */
    public ModelGateway() {
        this(GatewayConfig.fromEnvironment());
    }

    public ModelGateway(GatewayConfig config) {
        this(config, new HttpTransport(config));
    }

    public ModelGateway(GatewayConfig config, OutboundTransport transport) {
        this(config, transport, new CallAudit(), defaultContracts());
    }

    /** 注入自定义审计表（测试与运维排障用），任务契约仍取默认注册表。 */
    public ModelGateway(GatewayConfig config, OutboundTransport transport, CallAudit audit) {
        this(config, transport, audit, defaultContracts());
    }

    public ModelGateway(GatewayConfig config, OutboundTransport transport,
                        CallAudit audit, Map<String, TaskContract> contracts) {
        this.config = config;
        this.transport = transport;
        this.audit = audit;
        this.contracts = contracts;
    }

    /** 默认注册三类任务契约；业务可注入自定义注册表。 */
    public static Map<String, TaskContract> defaultContracts() {
        Map<String, TaskContract> m = new LinkedHashMap<>();
        m.put(ExtractContract.TASK, new ExtractContract());
        m.put(AliasContract.TASK, new AliasContract());
        m.put(AttributeContract.TASK, new AttributeContract());
        return m;
    }

    public GatewayConfig getConfig() { return config; }
    public OutboundTransport getTransport() { return transport; }
    public CallAudit getAudit() { return audit; }

    @Override
    public JsonNode call(String task, String schemaVersion, JsonNode payload) {
        String sv = (schemaVersion == null || schemaVersion.isBlank())
                ? PROMPT_VERSION : schemaVersion;
        String indicatorCode = indicatorCodeOf(payload);
        String inputHash = inputHashOf(task, sv, payload);
        OutboundRequest request = buildRequest(task, sv, payload);
        long start = System.nanoTime();

        int[] retries = {0};
        boolean success = false;
        JsonNode result = null;
        AppException failure = null;
        try {
            OutboundResponse response = sendWithRetry(request, retries);
            result = processResponse(task, payload, response);
            success = true;
        } catch (AppException e) {
            failure = e;
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        audit.record(new CallMetadata(indicatorCode, task, sv,
                config.getModel(), config.getPromptVersion(), inputHash,
                elapsed, retries[0], success, false,
                failure == null ? null : failure.getCode()));
        if (failure != null) {
            throw failure; // 不吞异常
        }
        return result;
    }

    /** 发送并按 E_LLM_001 / E_LLM_002 做有限重试；返回 2xx 响应。重试次数写入 holder 供审计。 */
    private OutboundResponse sendWithRetry(OutboundRequest request, int[] retries) {
        int attempt = 0;
        int maxRetries = config.getMaxRetries();
        while (true) {
            try {
                OutboundResponse resp = transport.send(request);
                if (!resp.is2xx()) {
                    throw ErrorCodes.error(ErrorCodes.E_LLM_001,
                            "status", String.valueOf(resp.getStatusCode()));
                }
                retries[0] = attempt;
                return resp;
            } catch (AppException e) {
                if (isRetryable(e.getCode()) && attempt < maxRetries) {
                    attempt++;
                    retries[0] = attempt;
                    continue;
                }
                throw e;
            } catch (RuntimeException e) {
                // 传输替身抛裸异常时归为连接失败，同样纳入重试
                if (attempt < maxRetries) {
                    attempt++;
                    retries[0] = attempt;
                    continue;
                }
                throw new AppException(ErrorCodes.E_LLM_001,
                        "模型服务连接失败：" + e.getMessage(), e);
            }
        }
    }

    private static boolean isRetryable(String code) {
        return ErrorCodes.E_LLM_001.equals(code) || ErrorCodes.E_LLM_002.equals(code);
    }

    /** 响应解析 → 解包 → 空结果判定 → 本地 schema 校验。 */
    private JsonNode processResponse(String task, JsonNode requestPayload, OutboundResponse response) {
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            throw ErrorCodes.error(ErrorCodes.E_LLM_003);
        }
        JsonNode root = parseJson(body);
        if (root == null || root.isNull() || !root.isContainerNode()) {
            throw ErrorCodes.error(ErrorCodes.E_LLM_003);
        }
        JsonNode payload = unwrap(root);
        if (payload == null || payload.isNull() || !payload.isContainerNode()) {
            throw ErrorCodes.error(ErrorCodes.E_LLM_003);
        }
        if (payload.size() == 0) {
            throw ErrorCodes.error(ErrorCodes.E_LLM_005);
        }
        TaskContract contract = contracts.get(task);
        if (contract != null) {
            contract.validateResponse(payload, requestPayload);
        } else {
            // 任务11 本地 schema 校验基础：未注册任务仅要求响应为合法 JSON 容器
            ContractSchemas.requireContainer(payload, "模型响应");
        }
        return payload;
    }

    /** 解析文本为 JSON；解析失败统一归为 E_LLM_003，不猜测修复。 */
    private static JsonNode parseJson(String text) {
        try {
            return JsonSupport.parse(text);
        } catch (AppException e) {
            throw ErrorCodes.error(ErrorCodes.E_LLM_003);
        }
    }

    /**
     * OpenAI-compatible 信封解包：{@code choices[0].message.content} 或 {@code choices[0].text}
     * 为 JSON 文本时解析成契约对象/数组；已是容器则直接返回；无信封则把根当作契约本体。
     */
    private static JsonNode unwrap(JsonNode root) {
        if (root.isObject()) {
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode first = choices.get(0);
                JsonNode content = first.path("message").path("content");
                if (!content.isTextual() && !content.isContainerNode()) {
                    content = first.path("text"); // 兼容旧 completions 形态
                }
                if (content.isTextual()) {
                    String text = content.asText();
                    return text.isBlank() ? null : parseJson(text);
                }
                if (content.isContainerNode()) {
                    return content;
                }
            }
        }
        return root;
    }

    /** 结构化请求体固定三字段 + OpenAI-compatible 头。 */
    private OutboundRequest buildRequest(String task, String schemaVersion, JsonNode payload) {
        ObjectNode body = JsonSupport.object();
        body.put("task", task);
        body.put("schema_version", schemaVersion);
        body.set("payload", payload == null ? JsonSupport.object() : payload);
        return OutboundRequest.builder("POST", config.endpointUrl())
                .bearer(config.getApiKey())
                .header("Accept", "application/json")
                .header("X-LLM-Model", config.getModel())
                .jsonBody(JsonSupport.compact(body))
                .build();
    }

    /** 从 payload 提取指标编码，供审计按 indicator_code 反查；缺失时用占位符。 */
    static String indicatorCodeOf(JsonNode payload) {
        if (payload != null && payload.isObject()) {
            JsonNode c = payload.get("indicator_code");
            if (c != null && !c.isNull() && !c.asText().isBlank()) {
                return c.asText();
            }
        }
        return "<unknown>";
    }

    /** 输入哈希：任务 + schema 版本 + payload 规范化文本，保证同输入同哈希。 */
    static String inputHashOf(String task, String schemaVersion, JsonNode payload) {
        return Hashes.sha256Of(task, schemaVersion,
                payload == null ? "" : JsonSupport.compact(payload));
    }
}
