package com.infection.metric.recompute;

/**
 * 差异条件所在的口径位置（任务7、任务34）。
 *
 * <p>规则基线归因按此位置把「条件级差异」映射到业务维度：
 * 分子树 → 分子范围、分母树 → 分母口径、排除树 → 排除条件；
 * scope 字段（适用科室 / 适用期间 / 生效期）→ 科室范围 / 生效期。</p>
 */
public enum TreePosition {
    NUMERATOR("分子树"),
    DENOMINATOR("分母树"),
    EXCLUSION("排除树"),
    SCOPE_DEPT("适用科室"),
    SCOPE_PERIOD("适用期间"),
    EFFECTIVE_FROM("生效起"),
    EFFECTIVE_TO("生效止");

    private final String cn;

    TreePosition(String cn) {
        this.cn = cn;
    }

    public String getCn() {
        return cn;
    }

    /** 是否属于 scope 类字段（非条件树叶子）。 */
    public boolean isScope() {
        return this == SCOPE_DEPT || this == SCOPE_PERIOD
                || this == EFFECTIVE_FROM || this == EFFECTIVE_TO;
    }

    /** 是否属于排除条件树。 */
    public boolean isExclusion() {
        return this == EXCLUSION;
    }
}
