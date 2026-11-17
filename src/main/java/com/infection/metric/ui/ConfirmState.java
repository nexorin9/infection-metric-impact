package com.infection.metric.ui;

/** 逐条差异的人工确认状态：只有「已确认」才允许提交复核。 */
public enum ConfirmState {
    /** 尚未确认。 */
    UNSET,
    /** 已确认。 */
    CONFIRMED,
    /** 有疑问（仍需处理，视为未确认）。 */
    QUESTIONED
}
