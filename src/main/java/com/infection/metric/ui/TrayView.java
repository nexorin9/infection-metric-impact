package com.infection.metric.ui;

import com.infection.metric.common.AppException;

import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * 系统托盘的 Swing / AWT 视图（薄壳）：注册托盘图标、菜单与待复核气泡。
 *
 * <p>构造前统一经 {@link UiGuiGuard#requireGui(String)} 判断 headless；托盘不支持时直接返回，
 * 由 {@link TrayController} 的后台降级态接管，不崩溃、不创建真实托盘。测试只断言纯逻辑状态机，
 * <strong>不实例化本类、不创建真实 SystemTray</strong>。</p>
 */
public final class TrayView {

    private TrayIcon trayIcon;

    /** 真实托盘是否可用（桥接 SystemTray.isSupported()），headless 下返回 false。 */
    public static boolean systemTraySupported() {
        return SystemTray.isSupported();
    }

    public TrayView(TrayController controller) {
        UiGuiGuard.requireGui("系统托盘 TrayView");
        if (!systemTraySupported()) {
            return; // 托盘不可用：回退为后台运行，由控制器给出启动提示
        }
        SystemTray tray = SystemTray.getSystemTray();
        PopupMenu menu = new PopupMenu();
        for (String item : controller.menuItems()) {
            menu.add(new MenuItem(item));
        }
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        trayIcon = new TrayIcon(image, "院感指标口径影响分析", menu);
        trayIcon.setImageAutoSize(true);
        try {
            tray.add(trayIcon);
        } catch (AWTException e) {
            throw AppException.of("E_UI_TRAY", "托盘图标注册失败：" + e.getMessage());
        }
        TrayBubble bubble = controller.bubble();
        if (bubble.isShown()) {
            trayIcon.displayMessage("待复核提醒", bubble.getText(), TrayIcon.MessageType.INFO);
        }
    }

    public TrayIcon getTrayIcon() { return trayIcon; }
}
