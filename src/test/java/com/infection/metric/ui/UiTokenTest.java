package com.infection.metric.ui;

import com.infection.metric.common.AppException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 任务13（M8-A）：设计 token 自检、8px 栅格、字体层级与组件四态样式解析。 */
class UiTokenTest {

    @Test
    void 语义色板六类齐全且色值明确() {
        Map<String, Integer> c = UiTokens.semanticColors();
        assertEquals(6, c.size(), "主色/成功/警告/危险/文本/边框六类齐全");
        assertEquals(0x2563EB, c.get("主色"));
        assertEquals(0x16A34A, c.get("成功"));
        assertEquals(0xD97706, c.get("警告"));
        assertEquals(0xDC2626, c.get("危险"));
        assertEquals(0x1F2328, c.get("文本"));
        assertEquals(0xE5E7EB, c.get("边框"));
        assertEquals(6, new HashSet<>(c.values()).size(), "六类语义色互不相同");
    }

    @Test
    void 对比度计算符合WCAG且关键组合达可读下限() {
        double body = UiTokens.contrastRatio(UiTokens.TEXT, UiTokens.SURFACE);
        assertTrue(body >= UiTokens.MIN_TEXT_CONTRAST, "正文/白底对比度 " + body + " 应达 4.5");
        assertTrue(body > 10.0, "深色正文在白底应接近最高对比度");
        double btn = UiTokens.contrastRatio(UiTokens.ON_PRIMARY, UiTokens.PRIMARY);
        assertTrue(btn >= UiTokens.MIN_TEXT_CONTRAST, "主色按钮白字对比度 " + btn + " 应达 4.5");
        assertEquals(1.0, UiTokens.contrastRatio(UiTokens.PRIMARY, UiTokens.PRIMARY), 1e-9,
                "同色对比度恒为 1");
        assertEquals(body, UiTokens.contrastRatio(UiTokens.SURFACE, UiTokens.TEXT), 1e-9,
                "对比度与前后景顺序无关");
    }

    @Test
    void 间距全部落在8px栅格且非栅格值被拒() {
        assertTrue(UiTokens.validateSpacing().isEmpty(), "内置间距 token 全部落在 8px 栅格");
        assertEquals(8, UiTokens.GRID);
        assertTrue(UiTokens.isOnGrid(8));
        assertTrue(UiTokens.isOnGrid(24));
        assertTrue(UiTokens.isOnGrid(48));
        assertFalse(UiTokens.isOnGrid(10), "10px 不在栅格");
        assertFalse(UiTokens.isOnGrid(0));
        assertFalse(UiTokens.isOnGrid(-8));
        List<String> bad = UiTokens.validateSpacing(new int[]{8, 10, 16, 22});
        assertEquals(2, bad.size(), "10 与 22 两个非栅格值被逐条指出");
        assertTrue(bad.get(0).contains("10"));
        assertTrue(bad.get(1).contains("22"));
    }

    @Test
    void token自检全通过且逐条给出对比度下限() {
        List<UiTokens.TokenCheck> checks = UiTokens.colorSelfCheck();
        assertTrue(checks.size() >= 8, "覆盖正文/辅助/按钮/危险/四类 UI 语义色组合");
        assertTrue(checks.stream().allMatch(UiTokens.TokenCheck::isPassed),
                "所有自检组合对比度达标");
        assertTrue(checks.stream().allMatch(ch -> ch.getRatio() >= ch.getMin()),
                "每条自检的实测对比度不低于其下限");
        assertTrue(UiTokens.selfCheckPassed());
        assertDoesNotThrow(UiTokens::assertSelfCheck);
        UiTokens.TokenCheck primary = checks.stream()
                .filter(ch -> ch.getName().contains("主色按钮"))
                .findFirst().orElseThrow();
        assertTrue(primary.getRatio() >= 4.5);
        assertTrue(primary.getName().contains("主色按钮"));
    }

    @Test
    void 字体层级分明且等宽字体独立() {
        assertTrue(UiTokens.SIZE_TITLE > UiTokens.SIZE_BODY);
        assertTrue(UiTokens.SIZE_BODY > UiTokens.SIZE_CAPTION);
        assertEquals(UiTokens.SIZE_CAPTION, UiTokens.SIZE_MONO);
        assertEquals("Monospaced", UiTokens.FONT_MONO);
        assertEquals("SansSerif", UiTokens.FONT_SANS);
        assertEquals("BOLD", UiTokens.WEIGHT_TITLE);
    }

    @Test
    void 四态样式解析覆盖按钮与表格行() {
        UiStateStyle def = UiComponents.buttonStyle(UiState.DEFAULT);
        UiStateStyle hover = UiComponents.buttonStyle(UiState.HOVER);
        UiStateStyle active = UiComponents.buttonStyle(UiState.ACTIVE);
        UiStateStyle disabled = UiComponents.buttonStyle(UiState.DISABLED);
        UiStateStyle loading = UiComponents.buttonStyle(UiState.LOADING);
        assertEquals(UiTokens.PRIMARY, def.getBackground());
        assertNotEquals(def.getBackground(), hover.getBackground(), "hover 改色");
        assertNotEquals(hover.getBackground(), active.getBackground(), "active 再改色");
        assertFalse(disabled.isEnabled(), "disabled 态不可交互");
        assertTrue(loading.isShowProgress(), "loading 态显示进度提示");
        assertTrue(loading.getHint().contains("处理中"));
        assertFalse(loading.isEnabled(), "loading 期间不可重复触发");

        assertFalse(UiComponents.rowStyle(UiState.DISABLED).isEnabled(), "表格行覆盖 disabled");
        assertTrue(UiComponents.rowStyle(UiState.LOADING).isShowProgress(), "表格行覆盖 loading");
        assertEquals(UiTokens.SURFACE, UiComponents.rowStyle(UiState.DEFAULT).getBackground());
        assertEquals(5, UiState.values().length, "默认态 + hover/active/disabled/loading 四态");
    }

    @Test
    void 组件工厂统一从token取值() {
        Map<String, Integer> p = UiComponents.buttonPalette();
        assertEquals(3, p.size());
        assertEquals(UiTokens.PRIMARY, p.get("background"));
        assertEquals(UiTokens.ON_PRIMARY, p.get("foreground"));
        assertEquals(UiTokens.BORDER, p.get("border"));
        assertEquals(UiTokens.TEXT_MUTED,
                UiComponents.buttonStyle(UiState.DISABLED).getForeground(),
                "按钮禁用态文字同样来自 token");
    }

    @Test
    void headless守卫给出明确提示而不崩溃() {
        String d = UiGuiGuard.describe();
        assertTrue(d.contains("图形界面"));
        assertTrue(d.length() > 10, "提示含可执行降级指引而非空串");
        if (UiGuiGuard.isHeadless()) {
            AppException ex = assertThrows(AppException.class,
                    () -> UiGuiGuard.requireGui("测试组件"));
            assertEquals("E_UI_HEADLESS", ex.getCode());
            assertTrue(ex.getMessage().contains("headless") || ex.getMessage().contains("无图形界面"));
        } else {
            assertDoesNotThrow(() -> UiGuiGuard.requireGui("测试组件"));
        }
    }
}
