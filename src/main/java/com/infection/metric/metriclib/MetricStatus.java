package com.infection.metric.metriclib;

import com.infection.metric.common.ErrorCodes;

import java.util.List;
import java.util.Set;

/** 口径条目状态机：DRAFT → PENDING_REVIEW → PUBLISHED / REJECTED → RETIRED，且 REJECTED → DRAFT。 */
public enum MetricStatus {
    DRAFT,
    PENDING_REVIEW,
    PUBLISHED,
    REJECTED,
    RETIRED;

    public boolean canTransitionTo(MetricStatus target) {
        if (target == null) return false;
        return switch (this) {
            case DRAFT -> target == PENDING_REVIEW;
            case PENDING_REVIEW -> target == PUBLISHED || target == REJECTED;
            case REJECTED -> target == DRAFT;
            case PUBLISHED -> target == RETIRED;
            case RETIRED -> false;
        };
    }

    public Set<MetricStatus> allowedTargets() {
        return Set.copyOf(List.of(values()).stream().filter(this::canTransitionTo).toList());
    }

    /** 非法跃迁抛 E_STATE_001 且状态不变（由调用方在校验通过后才写回）。 */
    public void requireTransition(MetricStatus target) {
        if (!canTransitionTo(target)) {
            throw ErrorCodes.error(ErrorCodes.E_STATE_001,
                    "from", name(), "to", target == null ? "null" : target.name());
        }
    }

    public static MetricStatus of(String raw) {
        if (raw == null) return DRAFT;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ErrorCodes.error(ErrorCodes.E_STATE_001, "from", "?", "to", raw);
        }
    }
}
