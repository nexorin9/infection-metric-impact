package com.infection.metric.connect;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;

/** 出站错误四分类：连接失败 / 超时 / 4xx / 5xx，各自带可读原因与错误码，错误不吞。 */
public final class FailureClassifier {

    public enum Kind { CONNECTION, TIMEOUT, CLIENT_4XX, SERVER_5XX, OK }

    private FailureClassifier() {}

    public static Kind of(int status, boolean connectionFailed, boolean timedOut) {
        if (timedOut) return Kind.TIMEOUT;
        if (connectionFailed) return Kind.CONNECTION;
        if (status >= 500) return Kind.SERVER_5XX;
        if (status >= 400) return Kind.CLIENT_4XX;
        return Kind.OK;
    }

    public static AppException error(Kind kind, String endpoint, int status) {
        return switch (kind) {
            case CONNECTION -> ErrorCodes.error(ErrorCodes.E_OUTBOUND_001, "endpoint", endpoint);
            case TIMEOUT -> ErrorCodes.error(ErrorCodes.E_OUTBOUND_002, "endpoint", endpoint);
            case CLIENT_4XX -> ErrorCodes.error(ErrorCodes.E_OUTBOUND_003,
                    "endpoint", endpoint, "status", String.valueOf(status));
            case SERVER_5XX -> ErrorCodes.error(ErrorCodes.E_OUTBOUND_004,
                    "endpoint", endpoint, "status", String.valueOf(status));
            case OK -> throw new IllegalArgumentException("成功响应不构成失败");
        };
    }

    /** 仅 5xx / 超时 / 连接失败可重试，4xx 不重试。 */
    public static boolean retryable(Kind kind) {
        return kind == Kind.SERVER_5XX || kind == Kind.TIMEOUT || kind == Kind.CONNECTION;
    }
}
