package com.infection.metric.ui;

import com.infection.metric.common.AppException;

import java.awt.GraphicsEnvironment;

/**
 * headless 守卫：所有 Swing 视图类构造前先调用 {@link #requireGui(String)}。
 *
 * <p>无图形界面时抛带明确提示的 AppException，而不是让 JFrame / JDialog / SystemTray 抛
 * HeadlessException 崩溃。本类自身为纯逻辑，仅读取 {@code GraphicsEnvironment.isHeadless()}，
 * 不创建任何 AWT 组件，可在测试中安全调用。</p>
 */
public final class UiGuiGuard {

    private UiGuiGuard() {}

    /** 当前是否无图形界面（headless 测试环境恒为 true）。 */
    public static boolean isHeadless() {
        return GraphicsEnvironment.isHeadless();
    }

    /** 面向用户的环境说明，headless 时给出可执行的降级指引。 */
    public static String describe() {
        return isHeadless()
                ? "当前为无图形界面（headless）环境，Swing 窗口与系统托盘不可用；请使用纯逻辑模型，或在带图形界面的环境执行 ./run.sh。"
                : "当前为图形界面环境，可创建 Swing 组件与系统托盘。";
    }

    /** 视图 / 组件工厂的统一入口：headless 时抛明确提示，不创建组件。 */
    public static void requireGui(String componentName) {
        if (isHeadless()) {
            throw AppException.of("E_UI_HEADLESS",
                    "无法创建 Swing 组件「" + componentName + "」：" + describe());
        }
    }
}
