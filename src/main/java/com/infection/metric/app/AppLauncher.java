package com.infection.metric.app;

import com.infection.metric.ui.DesktopShell;

import java.util.ArrayList;
import java.util.List;

/**
 * 院感指标口径影响分析 · 入口。
 *
 * <p>默认形态是桌面常驻窗口；{@code --headless} / {@code --doctor} / {@code --offline-sample}
 * 是给运维排障与离线演示用的通道，默认路径不指向任何本地替身。</p>
 */
public final class AppLauncher {

    public static final String MODE_HEADLESS = "--headless";
    public static final String MODE_DOCTOR = "--doctor";
    public static final String MODE_OFFLINE_SAMPLE = "--offline-sample";
    public static final String MODE_HELP = "--help";

    private AppLauncher() {
    }

    public static void main(String[] args) throws java.io.IOException {
        List<String> flags = new ArrayList<>();
        for (String arg : args) {
            if (arg != null && !arg.isBlank()) {
                flags.add(arg.trim());
            }
        }

        try {
            if (flags.contains(MODE_HELP)) {
                printUsage();
                return;
            }
            if (flags.contains(MODE_DOCTOR)) {
                DoctorRunner.run();
                return;
            }
            if (flags.contains(MODE_OFFLINE_SAMPLE) && flags.contains(MODE_HEADLESS)) {
                // 离线样例 + 复算：真跑一遍引擎并落盘运行摘要 / 影响清单 / 差异说明
                OfflineSampleRunner.runRecompute();
                return;
            }
            if (flags.contains(MODE_OFFLINE_SAMPLE)) {
                OfflineSampleRunner.run();
                return;
            }
            if (flags.contains(MODE_HEADLESS)) {
                HeadlessRunner.run();
                return;
            }
            DesktopShell.launch();
        } catch (RuntimeException e) {
            // 失败不静默：入口层给出可读原因并以非零码退出。
            System.err.println("[院感口径影响分析] 启动失败：" + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("[院感口径影响分析] 原因：" + e.getCause().getMessage());
            }
            System.exit(1);
        }
    }

    public static void printUsage() {
        System.out.println("院感指标口径影响分析");
        System.out.println("  ./run.sh                     启动桌面窗口（默认形态）");
        System.out.println("  ./run.sh --headless          跑一次复算并打印运行摘要");
        System.out.println("  ./run.sh --doctor            配置项、凭据位、端点可达性自检");
        System.out.println("  ./run.sh --offline-sample    离线演示（读脱敏样例，非默认路径）");
        System.out.println("  ./run.sh --offline-sample --headless   离线真跑复算并落盘运行摘要与差异说明");
        System.out.println("  ./run.sh --help              显示本说明");
    }
}
