package com.infection.metric.ui;

/** 操作反馈的三种状态：进行中 / 成功 / 失败。 */
public enum FeedbackState {
    /** 操作进行中。 */
    RUNNING,
    /** 操作成功。 */
    SUCCESS,
    /** 操作失败（给出可读原因与运行摘要入口）。 */
    FAILURE
}
