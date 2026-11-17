package com.infection.metric.ui;

/** 需要加载 / 空 / 错误三态的四处界面区域。 */
public enum ViewArea {
    /** 口径库树。 */
    LIBRARY_TREE("口径库树"),
    /** 详情面板。 */
    DETAIL("详情"),
    /** 影响面表。 */
    IMPACT_TABLE("影响面表"),
    /** 差异说明。 */
    DIFF_PANEL("差异说明");

    private final String label;

    ViewArea(String label) { this.label = label; }

    public String getLabel() { return label; }
}
