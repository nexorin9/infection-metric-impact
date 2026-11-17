package com.infection.metric.recompute;

/**
 * 差异业务维度枚举（任务34）：机器语言的条件差异 → 主任能读的业务维度。
 */
public enum BusinessDimension {
    NUMERATOR_SCOPE("分子范围"),
    DENOMINATOR_DEFINITION("分母口径"),
    EXCLUSION_CONDITION("排除条件"),
    DEPT_SCOPE("科室范围"),
    EFFECTIVE_PERIOD("生效期");

    private final String cn;

    BusinessDimension(String cn) {
        this.cn = cn;
    }

    public String getCn() {
        return cn;
    }

    /** 按枚举名或中文名解析；非法值返回 null（供模型候选的本地 schema 校验使用）。 */
    public static BusinessDimension of(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        for (BusinessDimension d : values()) {
            if (d.name().equalsIgnoreCase(s) || d.getCn().equals(s)) return d;
        }
        return null;
    }
}
