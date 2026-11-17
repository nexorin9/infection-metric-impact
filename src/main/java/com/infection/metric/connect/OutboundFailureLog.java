package com.infection.metric.connect;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 出站结构化日志与运行摘要接线：失败可观测、令牌不落日志、失败不按 0 计入分母。 */
public final class OutboundFailureLog {

    public static final class Entry {
        private final String endpoint, method, errorCode, message;
        private final int status, retryCount;
        private final long elapsedMillis;
        public Entry(String endpoint, String method, int status, long elapsedMillis,
                     int retryCount, String errorCode, String message) {
            this.endpoint = endpoint; this.method = method; this.status = status;
            this.elapsedMillis = elapsedMillis; this.retryCount = retryCount;
            this.errorCode = errorCode == null ? "" : errorCode; this.message = message == null ? "" : message;
        }
        public String getEndpoint() { return endpoint; }
        public String getMethod() { return method; }
        public int getStatus() { return status; }
        public long getElapsedMillis() { return elapsedMillis; }
        public int getRetryCount() { return retryCount; }
        public String getErrorCode() { return errorCode; }
        public String getMessage() { return message; }
        public boolean isFailure() { return !errorCode.isBlank(); }
    }

    public static final class FailedStep {
        private final String indicatorCode;
        private final String step;
        private final String reason;
        /** 数值留空代表显式失败，绝不写 0。 */
        private final Integer numerator = null;
        private final Integer denominator = null;
        public FailedStep(String indicatorCode, String step, String reason) {
            this.indicatorCode = indicatorCode; this.step = step; this.reason = reason;
        }
        public String getIndicatorCode() { return indicatorCode; }
        public String getStep() { return step; }
        public String getReason() { return reason; }
        public Integer getNumerator() { return numerator; }
        public Integer getDenominator() { return denominator; }
        public com.fasterxml.jackson.databind.node.ObjectNode toJson() {
            var o = com.infection.metric.common.JsonSupport.object();
            o.put("indicator_code", indicatorCode);
            o.put("step", step);
            o.put("reason", reason);
            o.putNull("numerator");
            o.putNull("denominator");
            o.put("status", "FAILED");
            return o;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<FailedStep> failedSteps = new ArrayList<>();

    /** 记录一次外发；日志中令牌一律脱敏。 */
    public void record(OutboundRequest req, OutboundResponse resp) {
        String endpoint = sanitize(req.getUrl());
        entries.add(new Entry(endpoint, req.getMethod(), resp.getStatusCode(),
                resp.getElapsedMillis(), resp.getRetryCount(), "", "成功"));
    }

    public void recordFailure(OutboundRequest req, AppException error) {
        String endpoint = sanitize(req.getUrl());
        String msg = sanitize(error.render());
        entries.add(new Entry(endpoint, req.getMethod(), 0, 0L, 0, error.getCode(), msg));
    }

    /** 把外发失败计入摘要失败步骤；指标级数值留空，不按 0 计入分母。 */
    public void bridgeToSummary(String indicatorCode, String step, AppException error) {
        failedSteps.add(new FailedStep(indicatorCode, step, sanitize(error.render())));
    }

    /** 脱敏：抹掉 Bearer 令牌、api_key 等敏感串，避免明文进日志。 */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String out = raw.replaceAll("(?i)(Bearer\\s+)[^\\s,;\"']+", "$1***");
        out = out.replaceAll("(?i)(api[_-]?key|token|authorization)\\s*[:=]\\s*[^\\s,;\"']+", "$1=***");
        return out;
    }

    public List<Entry> entries() { return Collections.unmodifiableList(entries); }
    public List<FailedStep> failedSteps() { return Collections.unmodifiableList(failedSteps); }

    /** 摘要片段：失败步骤逐条可读，数值为 null。 */
    public Map<String, Object> summaryFragment() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outbound_calls", entries.size());
        m.put("outbound_failures", entries.stream().filter(Entry::isFailure).count());
        m.put("failed_steps", failedSteps.stream().map(FailedStep::toJson).toList());
        return m;
    }
}
