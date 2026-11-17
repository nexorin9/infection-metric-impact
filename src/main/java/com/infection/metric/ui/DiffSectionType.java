package com.infection.metric.ui;

/** 差异说明的三段：分子差 / 分母差 / 口径差。 */
public enum DiffSectionType {
    /** 本期分子与旧口径分子的差。 */
    NUMERATOR("分子差"),
    /** 本期分母与旧口径分母的差。 */
    DENOMINATOR("分母差"),
    /** 条件级差异清单（口径差）。 */
    DEFINITION("口径差");

    private final String label;

    DiffSectionType(String label) { this.label = label; }

    public String getLabel() { return label; }
}
