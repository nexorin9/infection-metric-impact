package com.infection.metric.ui;

import com.infection.metric.common.AppException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M8-A 设计 token：界面观感的唯一取值来源。
 *
 * <p>色板与语义色集中在此；间距遵循 8px 栅格；字体层级分标题 / 正文 / 辅助 / 等宽。
 * 本类为纯逻辑模型，不引用任何 Swing 组件，可在 headless 测试环境安全使用；
 * Swing 视图通过 {@code new Color(rgb)} 把这里的 0xRRGGBB 色值转成 AWT 颜色。</p>
 *
 * <p>自检：{@link #contrastRatio(int, int)} 按 WCAG 相对亮度计算对比度，
 * {@link #validateSpacing()} 校验间距全部落在栅格上，自检结果供单测断言。</p>
 */
public final class UiTokens {

    private UiTokens() {}

    // ===== 语义色板（0xRRGGBB）=====
    /** 主色：主按钮、选中强调。 */
    public static final int PRIMARY = 0x2563EB;
    /** 主色 hover。 */
    public static final int PRIMARY_HOVER = 0x1D4ED8;
    /** 主色 active（按下）。 */
    public static final int PRIMARY_ACTIVE = 0x1E40AF;
    /** 成功语义色。 */
    public static final int SUCCESS = 0x16A34A;
    /** 警告语义色。 */
    public static final int WARNING = 0xD97706;
    /** 危险语义色。 */
    public static final int DANGER = 0xDC2626;
    /** 主文本色。 */
    public static final int TEXT = 0x1F2328;
    /** 辅助 / 弱化文本色。 */
    public static final int TEXT_MUTED = 0x6B7280;
    /** 边框 / 分割线色。 */
    public static final int BORDER = 0xE5E7EB;
    /** 面板表面色。 */
    public static final int SURFACE = 0xFFFFFF;
    /** 窗口画布底色。 */
    public static final int CANVAS = 0xF7F8FA;
    /** 表格行选中色。 */
    public static final int SELECTED = 0xDDEBFF;
    /** 主色 / 危险色之上的反白文字。 */
    public static final int ON_PRIMARY = 0xFFFFFF;

    // ===== 8px 间距栅格 =====
    public static final int GRID = 8;
    public static final int SPACE_1 = 8;
    public static final int SPACE_2 = 16;
    public static final int SPACE_3 = 24;
    public static final int SPACE_4 = 32;
    public static final int SPACE_5 = 40;
    public static final int SPACE_6 = 48;
    /** 内置全部间距 token，供栅格自检。 */
    public static final int[] SPACING = {SPACE_1, SPACE_2, SPACE_3, SPACE_4, SPACE_5, SPACE_6};

    // ===== 字体层级 =====
    public static final String FONT_SANS = "SansSerif";
    public static final String FONT_MONO = "Monospaced";
    public static final int SIZE_TITLE = 18;
    public static final int SIZE_SUBTITLE = 14;
    public static final int SIZE_BODY = 13;
    public static final int SIZE_CAPTION = 12;
    public static final int SIZE_MONO = 12;
    public static final String WEIGHT_TITLE = "BOLD";
    public static final String WEIGHT_BODY = "PLAIN";

    // ===== 可读性下限 =====
    /** 正文文本对比度下限（WCAG AA）。 */
    public static final double MIN_TEXT_CONTRAST = 4.5;
    /** 非文本 UI 组件 / 大字对比度下限。 */
    public static final double MIN_UI_CONTRAST = 3.0;

    /** 六类语义色（主色 / 成功 / 警告 / 危险 / 文本 / 边框），供视图与自检统一取值。 */
    public static Map<String, Integer> semanticColors() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("主色", PRIMARY);
        m.put("成功", SUCCESS);
        m.put("警告", WARNING);
        m.put("危险", DANGER);
        m.put("文本", TEXT);
        m.put("边框", BORDER);
        return Collections.unmodifiableMap(m);
    }

    /** 把 0xRRGGBB 通道线性化后取 WCAG 相对亮度。 */
    public static double relativeLuminance(int rgb) {
        double r = channel(((rgb >> 16) & 0xFF) / 255.0);
        double g = channel(((rgb >> 8) & 0xFF) / 255.0);
        double b = channel((rgb & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(double c) {
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** 两色对比度（顺序无关，取值 1.0 ~ 21.0）。 */
    public static double contrastRatio(int fg, int bg) {
        double l1 = relativeLuminance(fg);
        double l2 = relativeLuminance(bg);
        double hi = Math.max(l1, l2);
        double lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** 是否落在 8px 栅格上（正整数且为 8 的倍数）。 */
    public static boolean isOnGrid(int value) {
        return value > 0 && value % GRID == 0;
    }

    /** 校验内置间距 token 全集，返回违规描述；空列表表示全部合规。 */
    public static List<String> validateSpacing() {
        return validateSpacing(SPACING);
    }

    /** 校验任意一组间距值是否落在 8px 栅格上。 */
    public static List<String> validateSpacing(int[] values) {
        List<String> bad = new ArrayList<>();
        if (values == null) {
            bad.add("间距集合为空");
            return bad;
        }
        for (int v : values) {
            if (!isOnGrid(v)) {
                bad.add("间距 " + v + "px 不在 8px 栅格上");
            }
        }
        return bad;
    }

    /** 单条语义色对比度自检结果。 */
    public static final class TokenCheck {
        private final String name;
        private final int fg;
        private final int bg;
        private final double ratio;
        private final double min;
        private final boolean passed;
        public TokenCheck(String name, int fg, int bg, double ratio, double min, boolean passed) {
            this.name = name;
            this.fg = fg;
            this.bg = bg;
            this.ratio = ratio;
            this.min = min;
            this.passed = passed;
        }
        public String getName() { return name; }
        public int getFg() { return fg; }
        public int getBg() { return bg; }
        public double getRatio() { return ratio; }
        public double getMin() { return min; }
        public boolean isPassed() { return passed; }
    }

    /** 关键文本 / UI 组合的色值对比度自检，结果供单测逐条断言。 */
    public static List<TokenCheck> colorSelfCheck() {
        List<TokenCheck> out = new ArrayList<>();
        out.add(check("正文/白底", TEXT, SURFACE, MIN_TEXT_CONTRAST));
        out.add(check("辅助文本/白底", TEXT_MUTED, SURFACE, MIN_TEXT_CONTRAST));
        out.add(check("正文/画布底", TEXT, CANVAS, MIN_TEXT_CONTRAST));
        out.add(check("主色按钮文字", ON_PRIMARY, PRIMARY, MIN_TEXT_CONTRAST));
        out.add(check("危险按钮文字", ON_PRIMARY, DANGER, MIN_TEXT_CONTRAST));
        out.add(check("主色/白底(UI)", PRIMARY, SURFACE, MIN_UI_CONTRAST));
        out.add(check("成功/白底(UI)", SUCCESS, SURFACE, MIN_UI_CONTRAST));
        out.add(check("警告/白底(UI)", WARNING, SURFACE, MIN_UI_CONTRAST));
        out.add(check("危险/白底(UI)", DANGER, SURFACE, MIN_UI_CONTRAST));
        return out;
    }

    private static TokenCheck check(String name, int fg, int bg, double min) {
        double ratio = contrastRatio(fg, bg);
        return new TokenCheck(name, fg, bg, ratio, min, ratio >= min);
    }

    /** 综合自检：色值对比度达标且间距全在栅格上。 */
    public static boolean selfCheckPassed() {
        return validateSpacing().isEmpty()
                && colorSelfCheck().stream().allMatch(TokenCheck::isPassed);
    }

    /** 自检未通过时抛 AppException，绝不带着不合规 token 静默进入视图层。 */
    public static void assertSelfCheck() {
        if (!selfCheckPassed()) {
            throw AppException.of("E_UI_TOKEN",
                    "设计 token 自检未通过：存在对比度不达可读下限或间距不在 8px 栅格上的取值。");
        }
    }
}
