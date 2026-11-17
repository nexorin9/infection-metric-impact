package com.infection.metric.pkg;

import com.infection.metric.common.ErrorCodes;

/**
 * 口径包状态机：IMPORTED → REVIEWED → MERGED / REJECTED。
 *
 * <p>与口径条目状态机同款语义：每次跃迁校验前置状态，非法跃迁抛
 * {@link ErrorCodes#E_PKG_002} 且状态不变（调用方只在校验通过后才写回）。
 * MERGED / REJECTED 为终态，不再向外跃迁；合并事务回滚会把包状态回退为
 * REVIEWED，那是一次显式的事务补偿动作，不属于本状态机的常规跃迁。</p>
 */
public enum PackageStatus {
    IMPORTED,
    REVIEWED,
    MERGED,
    REJECTED;

    /** 是否允许从当前状态跃迁到目标状态。 */
    public boolean canTransitionTo(PackageStatus target) {
        if (target == null) return false;
        return switch (this) {
            case IMPORTED -> target == REVIEWED;
            case REVIEWED -> target == MERGED || target == REJECTED;
            case MERGED, REJECTED -> false;
        };
    }

    /** 非法跃迁抛 E_PKG_002，状态由调用方保持不变。 */
    public void requireTransition(PackageStatus target) {
        if (!canTransitionTo(target)) {
            throw ErrorCodes.error(ErrorCodes.E_PKG_002,
                    "from", name(), "to", target == null ? "null" : target.name());
        }
    }

    public static PackageStatus of(String raw) {
        if (raw == null || raw.isBlank()) return IMPORTED;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ErrorCodes.error(ErrorCodes.E_PKG_002, "from", "?", "to", raw);
        }
    }
}
