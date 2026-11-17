package com.infection.metric.ui;

/** M8-A 组件交互态：在默认态之上覆盖 hover / active / disabled / loading 四态。 */
public enum UiState {
    /** 默认可交互态。 */
    DEFAULT,
    /** 指针悬停。 */
    HOVER,
    /** 按下 / 激活。 */
    ACTIVE,
    /** 禁用，不可交互。 */
    DISABLED,
    /** 加载中，显示进度且不可重复触发。 */
    LOADING
}
