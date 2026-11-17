package com.infection.metric.ui;

import com.infection.metric.app.MainWindow;

import java.awt.GraphicsEnvironment;
import java.awt.Insets;

/**
 * 桌面常驻窗口的入口外壳：骨架与全部三栏内容由 {@link MainWindow} 装配。
 *
 * <p>窗口数据来自一次真实落盘的复算运行（口径库 / 影响面 / 差异说明），
 * 不是静态占位界面；headless 环境明确报错并提示改用 CLI 通道。</p>
 */
public final class DesktopShell {

    private DesktopShell() {
    }

    public static void launch() {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("[院感口径影响分析] 当前环境无图形界面，请使用 ./run.sh --headless 或 --doctor。");
            System.exit(3);
            return;
        }
        MainWindow.create().frame().setVisible(true);
    }

    /** 供后续任务复用：统一的内边距。 */
    public static Insets padding(int all) {
        return new Insets(all, all, all, all);
    }
}
