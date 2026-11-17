package com.infection.metric.ui;

/** 区域视图三态（加内容基线态）：加载 / 空 / 错误 / 正常内容。 */
public enum ViewState {
    /** 加载中：显示进度或骨架屏。 */
    LOADING,
    /** 空态：明确引导文案。 */
    EMPTY,
    /** 错误态：可读原因 + 重试按钮。 */
    ERROR,
    /** 正常内容态。 */
    CONTENT
}
