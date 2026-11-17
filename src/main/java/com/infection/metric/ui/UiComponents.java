package com.infection.metric.ui;

import com.infection.metric.common.AppException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.Color;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M8-A 基础组件：按钮 / 输入框 / 下拉 / 表格 / 徽标 / 分割线的 Swing 工厂方法与状态样式解析。
 *
 * <p>纯逻辑部分（{@link #buttonStyle(UiState)} / {@link #rowStyle(UiState)} /
 * {@link #buttonPalette()}）不创建任何组件，headless 测试可直接断言；
 * Swing 工厂方法首行统一调用 {@link UiGuiGuard#requireGui(String)}，无图形界面时抛明确提示。</p>
 *
 * <p>所有色值 / 间距 / 字号一律从 {@link UiTokens} 取值，不残留系统默认样式。</p>
 */
public final class UiComponents {

    private UiComponents() {}

    // ===== 纯逻辑：四态样式解析（headless 安全） =====

    /** 按钮四态样式（含默认态），颜色全部来自 token。 */
    public static UiStateStyle buttonStyle(UiState state) {
        requireState(state);
        return switch (state) {
            case DEFAULT  -> new UiStateStyle(UiTokens.PRIMARY, UiTokens.ON_PRIMARY, true, false, "");
            case HOVER    -> new UiStateStyle(UiTokens.PRIMARY_HOVER, UiTokens.ON_PRIMARY, true, false, "悬停");
            case ACTIVE   -> new UiStateStyle(UiTokens.PRIMARY_ACTIVE, UiTokens.ON_PRIMARY, true, false, "按下");
            case DISABLED -> new UiStateStyle(UiTokens.BORDER, UiTokens.TEXT_MUTED, false, false, "当前操作不可用");
            case LOADING  -> new UiStateStyle(UiTokens.PRIMARY, UiTokens.ON_PRIMARY, false, true, "处理中，请稍候…");
        };
    }

    /** 表格行四态样式（含默认态），覆盖行级 hover / active / disabled / loading。 */
    public static UiStateStyle rowStyle(UiState state) {
        requireState(state);
        return switch (state) {
            case DEFAULT  -> new UiStateStyle(UiTokens.SURFACE, UiTokens.TEXT, true, false, "");
            case HOVER    -> new UiStateStyle(UiTokens.CANVAS, UiTokens.TEXT, true, false, "行悬停");
            case ACTIVE   -> new UiStateStyle(UiTokens.SELECTED, UiTokens.TEXT, true, false, "行选中");
            case DISABLED -> new UiStateStyle(UiTokens.CANVAS, UiTokens.TEXT_MUTED, false, false, "该行不可选");
            case LOADING  -> new UiStateStyle(UiTokens.CANVAS, UiTokens.TEXT_MUTED, false, true, "正在加载该行数据…");
        };
    }

    /** 默认按按钮解析；表格行视图可显式改调 {@link #rowStyle(UiState)}。 */
    public static UiStateStyle stateStyle(UiState state) {
        return buttonStyle(state);
    }

    private static void requireState(UiState state) {
        if (state == null) {
            throw AppException.of("E_UI_STATE", "组件交互状态不能为空。");
        }
    }

    /** 按钮配色规格，全部取自 token，供「统一观感」自检断言。 */
    public static Map<String, Integer> buttonPalette() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("background", UiTokens.PRIMARY);
        m.put("foreground", UiTokens.ON_PRIMARY);
        m.put("border", UiTokens.BORDER);
        return m;
    }

    // ===== Swing 工厂（headless 受保护，测试不实例化） =====

    public static JButton button(String text) {
        UiGuiGuard.requireGui("按钮 JButton");
        JButton b = new JButton(text);
        b.setBackground(new Color(buttonPalette().get("background")));
        b.setForeground(new Color(buttonPalette().get("foreground")));
        b.setFont(new Font(UiTokens.FONT_SANS, Font.BOLD, UiTokens.SIZE_BODY));
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_1, UiTokens.SPACE_2, UiTokens.SPACE_1, UiTokens.SPACE_2));
        return b;
    }

    public static JTextField input() {
        UiGuiGuard.requireGui("输入框 JTextField");
        JTextField f = new JTextField();
        f.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_BODY));
        f.setForeground(new Color(UiTokens.TEXT));
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(UiTokens.BORDER)),
                BorderFactory.createEmptyBorder(
                        UiTokens.SPACE_1, UiTokens.SPACE_1, UiTokens.SPACE_1, UiTokens.SPACE_1)));
        return f;
    }

    public static JComboBox<String> comboBox(String... items) {
        UiGuiGuard.requireGui("下拉 JComboBox");
        JComboBox<String> c = new JComboBox<>(items);
        c.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_BODY));
        c.setBackground(new Color(UiTokens.SURFACE));
        return c;
    }

    public static JTable table(String... columns) {
        UiGuiGuard.requireGui("表格 JTable");
        DefaultTableModel model = new DefaultTableModel(columns, 0);
        JTable t = new JTable(model);
        t.setRowHeight(UiTokens.SPACE_4);
        t.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_BODY));
        t.setGridColor(new Color(UiTokens.BORDER));
        t.setSelectionBackground(new Color(UiTokens.SELECTED));
        JTableHeader h = t.getTableHeader();
        h.setBackground(new Color(UiTokens.CANVAS));
        h.setFont(new Font(UiTokens.FONT_SANS, Font.BOLD, UiTokens.SIZE_CAPTION));
        return t;
    }

    public static JLabel badge(String text, int semanticRgb) {
        UiGuiGuard.requireGui("徽标 JLabel");
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        int fg = UiTokens.contrastRatio(UiTokens.ON_PRIMARY, semanticRgb) >= UiTokens.MIN_UI_CONTRAST
                ? UiTokens.ON_PRIMARY : UiTokens.TEXT;
        l.setOpaque(true);
        l.setBackground(new Color(semanticRgb));
        l.setForeground(new Color(fg));
        l.setFont(new Font(UiTokens.FONT_SANS, Font.BOLD, UiTokens.SIZE_CAPTION));
        l.setBorder(BorderFactory.createEmptyBorder(2, UiTokens.SPACE_1, 2, UiTokens.SPACE_1));
        return l;
    }

    public static JSeparator separator() {
        UiGuiGuard.requireGui("分割线 JSeparator");
        JSeparator s = new JSeparator();
        s.setForeground(new Color(UiTokens.BORDER));
        return s;
    }
}
