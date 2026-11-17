package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.metriclib.MetricStatus;

/**
 * 业务侧降级门面（任务46）：把「模型调用」包装成可降级结果。
 *
 * <p>{@link ModelGateway#call} 作为底层端口，对所有失败都抛 AppException、不吞异常、写审计。
 * 本门面只把「模型不可达」类错误（连接失败 / 超时 / 空结果）转成降级结果：
 * 口径来源标「人工」、发布状态原样保留、追加一条 {@code degraded=true} 审计记录。
 * 内容类错误（响应非 JSON / schema 校验失败）一律原样抛出，不降级、不重试、不猜测修复。</p>
 */
public final class GatewayInvoker {

    private final ModelGateway gateway;
    private final CallAudit audit;

    public GatewayInvoker(ModelGateway gateway) {
        this(gateway, gateway.getAudit());
    }

    public GatewayInvoker(ModelGateway gateway, CallAudit audit) {
        this.gateway = gateway;
        this.audit = audit;
    }

    public ModelGateway getGateway() { return gateway; }

    /**
     * 发起一次可降级模型调用。
     *
     * @param currentStatus 调用前的口径发布状态；无论成功或降级都不自动推进
     */
    public CallOutcome invoke(String task, String schemaVersion, JsonNode payload,
                              MetricStatus currentStatus) {
        try {
            JsonNode result = gateway.call(task, schemaVersion, payload);
            return CallOutcome.modelSuccess(result, currentStatus);
        } catch (AppException e) {
            String code = e.getCode();
            if (!isUnavailable(code)) {
                throw e; // 内容类错误不吞异常、不降级
            }
            String indicatorCode = ModelGateway.indicatorCodeOf(payload);
            String inputHash = ModelGateway.inputHashOf(task, schemaVersion, payload);
            audit.record(new CallMetadata(indicatorCode, task, schemaVersion,
                    gateway.getConfig().getModel(), gateway.getConfig().getPromptVersion(),
                    inputHash, 0L, 0, false, true, code));
            return CallOutcome.degraded(currentStatus, code);
        }
    }

    /** 可用性类错误：模型不可达时降级到人工。 */
    public static boolean isUnavailable(String code) {
        return ErrorCodes.E_LLM_001.equals(code)
                || ErrorCodes.E_LLM_002.equals(code)
                || ErrorCodes.E_LLM_005.equals(code);
    }
}
