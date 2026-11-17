package com.infection.metric.ui;

import java.util.List;

/**
 * 系统托盘常驻的纯逻辑状态机（不创建真实 SystemTray / TrayIcon，headless 安全）。
 *
 * <p>托盘菜单固定三项（打开主窗口 / 待复核条目 / 退出）；存在 PENDING_REVIEW 条目时气泡提示
 * 待办条数并给出点击直达目标。关闭主窗口只隐藏窗口、进程常驻，仅「退出」真正结束进程；
 * 托盘不可用时（由构造注入的 {@code traySupported=false} 模拟 SystemTray.isSupported() 返回 false）
 * 回退为后台降级态并给出启动提示，不崩溃。</p>
 */
public final class TrayController {

    /** 托盘菜单三项。 */
    public static final List<String> MENU_ITEMS =
            List.of("打开主窗口", "待复核条目", "退出");
    /** 气泡点击直达：待复核列表。 */
    public static final String BUBBLE_TARGET = "PENDING_REVIEW_LIST";

    private final boolean traySupported;
    private TrayState state;
    private int pendingCount;

    public TrayController(boolean traySupported, int pendingCount) {
        this.traySupported = traySupported;
        this.pendingCount = Math.max(0, pendingCount);
        this.state = traySupported
                ? TrayState.WINDOW_VISIBLE
                : TrayState.DEGRADED_BACKGROUND;
    }

    public List<String> menuItems() { return MENU_ITEMS; }
    public TrayState getState() { return state; }
    public boolean isTraySupported() { return traySupported; }
    public int getPendingCount() { return pendingCount; }

    public void setPendingCount(int n) { this.pendingCount = Math.max(0, n); }

    /** 待复核气泡：条数 > 0 才弹出，文案写明待办条数。 */
    public TrayBubble bubble() {
        boolean shown = pendingCount > 0;
        String text = shown
                ? "您有 " + pendingCount + " 条口径待复核，点击直达待复核列表"
                : "暂无待复核条目";
        return new TrayBubble(shown, text, BUBBLE_TARGET, pendingCount);
    }

    /** 关闭主窗口：只隐藏，进程常驻；降级态下继续保持后台。 */
    public TrayState onWindowClose() {
        if (state == TrayState.EXITED) {
            return state;
        }
        state = traySupported
                ? TrayState.WINDOW_HIDDEN
                : TrayState.DEGRADED_BACKGROUND;
        return state;
    }

    /** 托盘「打开主窗口」或气泡点击：重新显示窗口。 */
    public TrayState onOpenWindow() {
        if (state == TrayState.EXITED) {
            return state;
        }
        state = TrayState.WINDOW_VISIBLE;
        return state;
    }

    /** 仅托盘「退出」真正结束进程。 */
    public TrayState onExit() {
        state = TrayState.EXITED;
        return state;
    }

    public boolean isProcessAlive() { return state != TrayState.EXITED; }
    public boolean isWindowVisible() { return state == TrayState.WINDOW_VISIBLE; }
    public boolean isDegraded() { return state == TrayState.DEGRADED_BACKGROUND; }

    /** 启动提示：托盘不可用时明确说明降级路径，绝不静默崩溃。 */
    public String startupHint() {
        return traySupported
                ? "已启动并常驻系统托盘；关闭主窗口只会隐藏窗口，进程继续运行。"
                : "当前桌面环境不支持系统托盘，已最小化到后台运行；可通过 ./run.sh 重新打开主窗口，不会崩溃退出。";
    }
}
