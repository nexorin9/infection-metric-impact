package com.infection.metric.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务55（M8-F）：托盘菜单 / 待复核气泡 / 关闭语义状态机 / 托盘不可用降级。仅断言纯逻辑，不创建真实托盘。 */
class TrayTest {

    @Test
    void 托盘菜单含打开主窗口待复核条目与退出三项() {
        TrayController c = new TrayController(true, 0);
        List<String> menu = c.menuItems();
        assertEquals(3, menu.size());
        assertTrue(menu.containsAll(List.of("打开主窗口", "待复核条目", "退出")));
        assertEquals("退出", menu.get(2));
    }

    @Test
    void 存在待复核条目时气泡提示条数且点击直达列表() {
        TrayController c = new TrayController(true, 3);
        TrayBubble b = c.bubble();
        assertTrue(b.isShown());
        assertTrue(b.getText().contains("3"), "气泡写明待办条数");
        assertEquals(3, b.getPendingCount());
        assertEquals("PENDING_REVIEW_LIST", b.getTarget(), "点击直达待复核列表");
    }

    @Test
    void 无待复核条目不弹气泡且数量变化即时生效() {
        TrayController c = new TrayController(true, 0);
        TrayBubble b = c.bubble();
        assertFalse(b.isShown());
        assertEquals(0, b.getPendingCount());
        c.setPendingCount(2);
        assertTrue(c.bubble().isShown());
        assertEquals(2, c.bubble().getPendingCount());
        assertTrue(c.bubble().getText().contains("2"));
    }

    @Test
    void 关闭主窗口只隐藏窗口而进程常驻() {
        TrayController c = new TrayController(true, 1);
        assertEquals(TrayState.WINDOW_VISIBLE, c.getState());
        assertTrue(c.isWindowVisible());

        TrayState afterClose = c.onWindowClose();
        assertEquals(TrayState.WINDOW_HIDDEN, afterClose);
        assertTrue(c.isProcessAlive(), "关闭 X 不等于退出，进程常驻");
        assertFalse(c.isWindowVisible());

        assertEquals(TrayState.WINDOW_VISIBLE, c.onOpenWindow(), "托盘可重新打开主窗口");
        assertTrue(c.isWindowVisible());
    }

    @Test
    void 仅托盘退出真正结束进程() {
        TrayController c = new TrayController(true, 0);
        c.onWindowClose();
        assertTrue(c.isProcessAlive());
        assertEquals(TrayState.EXITED, c.onExit());
        assertFalse(c.isProcessAlive());
        assertEquals(TrayState.EXITED, c.onWindowClose(), "退出后再关闭窗口不再改变状态");
        assertEquals(TrayState.EXITED, c.onOpenWindow());
    }

    @Test
    void 托盘不可用降级为后台且给出启动提示不崩溃() {
        TrayController c = new TrayController(false, 1);
        assertFalse(c.isTraySupported());
        assertTrue(c.isDegraded());
        assertEquals(TrayState.DEGRADED_BACKGROUND, c.getState());
        assertTrue(c.isProcessAlive(), "降级后进程仍活，不崩溃");
        assertTrue(c.startupHint().contains("不支持系统托盘"));
        assertTrue(c.startupHint().contains("最小化到后台"));

        c.onWindowClose();
        assertEquals(TrayState.DEGRADED_BACKGROUND, c.getState(), "降级下关闭窗口仍留后台");
        assertTrue(c.isProcessAlive());
    }

    @Test
    void 降级环境下仍可提示待办且不显示主窗口() {
        TrayController c = new TrayController(false, 2);
        TrayBubble b = c.bubble();
        assertTrue(b.isShown());
        assertTrue(b.getText().contains("2"));
        assertEquals("PENDING_REVIEW_LIST", b.getTarget());
        assertFalse(c.isWindowVisible());
    }
}
