package com.infection.metric.ui;

import com.infection.metric.common.AppException;

/**
 * 两档响应式布局的纯计算（不创建 Swing 组件）：
 * 宽屏（≥1024，如 1280）三栏并列；窄屏（&lt;1024，如 768）折叠为两栏 + 抽屉（右侧差异说明入抽屉）。
 *
 * <p>布局只做纯计算，不读写 {@link UiStates} 的区域状态，因此切换档位不丢状态。
 * 两种模式的并列列宽之和加分隔条恒等于容器宽，保证无溢出、无截断。</p>
 */
public final class ResponsiveLayout {

    /** 三栏并列的最小宽度阈值。 */
    public static final int THREE_COLUMN_MIN_WIDTH = 1024;
    public static final String MODE_THREE_COLUMN = "THREE_COLUMN";
    public static final String MODE_TWO_COLUMN_DRAWER = "TWO_COLUMN_DRAWER";
    /** 窄屏两栏中左栏占比。 */
    public static final double TWO_COLUMN_LEFT_RATIO = 0.34;

    private ResponsiveLayout() {}

    public static LayoutSpec layoutFor(int width) {
        if (width < ThreeColumnLayout.MIN_WIDTH) {
            throw AppException.of("E_UI_LAYOUT",
                    "容器宽度 " + width + "px 过小，无法生成响应式布局。");
        }
        int divider = ThreeColumnLayout.DIVIDER;
        if (width >= THREE_COLUMN_MIN_WIDTH) {
            ThreeColumnLayout.ColumnSpec c = ThreeColumnLayout.compute(width);
            return new LayoutSpec(width, MODE_THREE_COLUMN,
                    c.getLeft(), c.getCenter(), c.getRight(), false, 2, divider);
        }
        int usable = width - divider;
        int left = (int) Math.round(usable * TWO_COLUMN_LEFT_RATIO);
        int center = usable - left;
        return new LayoutSpec(width, MODE_TWO_COLUMN_DRAWER,
                left, center, 0, true, 1, divider);
    }
}
