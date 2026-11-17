package com.infection.metric.ui;

/**
 * 托盘常驻状态机的四种状态。
 *
 * <p>关闭主窗口只把窗口隐藏、进程常驻；仅托盘「退出」才把状态推进到 EXITED 真正结束进程；
 * 托盘不可用时回退到后台降级态，进程依旧常驻。</p>
 */
public enum TrayState {
    /** 主窗口可见，进程运行。 */
    WINDOW_VISIBLE,
    /** 主窗口已隐藏（关闭 X），进程常驻托盘。 */
    WINDOW_HIDDEN,
    /** 桌面环境无托盘，最小化到后台运行。 */
    DEGRADED_BACKGROUND,
    /** 托盘「退出」后真正结束进程。 */
    EXITED
}
