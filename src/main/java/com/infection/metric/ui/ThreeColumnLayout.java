package com.infection.metric.ui;

import com.infection.metric.common.AppException;

/**
 * 三栏窗口骨架的纯逻辑布局计算：左侧口径库 / 中间详情与影响面 / 右侧差异说明。
 *
 * <p>按固定比例分配宽度、宽度取整后用「末列吸收余量」保证三栏之和加分隔条恰好等于容器宽，
 * 不产生溢出；支持左栏 / 右栏折叠（折叠后仅保留把手宽度），剩余空间按未折叠列比例重分。
 * 本类只做计算，不创建任何 Swing 组件。</p>
 */
public final class ThreeColumnLayout {

    /** 每条分隔条宽度。 */
    public static final int DIVIDER = 6;
    /** 折叠栏保留的把手宽度。 */
    public static final int COLLAPSED_HANDLE = 24;
    /** 可容纳三栏的最小容器宽。 */
    public static final int MIN_WIDTH = 3 * COLLAPSED_HANDLE + 2 * DIVIDER;

    public static final double LEFT_RATIO = 0.24;
    public static final double CENTER_RATIO = 0.46;
    public static final double RIGHT_RATIO = 0.30;

    private ThreeColumnLayout() {}

    /** 三栏都展开。 */
    public static ColumnSpec compute(int width) {
        return compute(width, false, false);
    }

    /** 指定左栏 / 右栏是否折叠。 */
    public static ColumnSpec compute(int width, boolean collapseLeft, boolean collapseRight) {
        if (width < MIN_WIDTH) {
            throw AppException.of("E_UI_LAYOUT",
                    "容器宽度 " + width + "px 过小，无法容纳三栏骨架（最小 " + MIN_WIDTH + "px）。");
        }
        int usable = width - 2 * DIVIDER;
        int left;
        int center;
        int right;

        if (collapseLeft && collapseRight) {
            left = COLLAPSED_HANDLE;
            right = COLLAPSED_HANDLE;
            center = usable - left - right;
        } else if (collapseLeft) {
            left = COLLAPSED_HANDLE;
            int rest = usable - left;
            center = (int) Math.round(rest * CENTER_RATIO / (CENTER_RATIO + RIGHT_RATIO));
            right = rest - center;
        } else if (collapseRight) {
            right = COLLAPSED_HANDLE;
            int rest = usable - right;
            left = (int) Math.round(rest * LEFT_RATIO / (LEFT_RATIO + CENTER_RATIO));
            center = rest - left;
        } else {
            left = (int) Math.round(usable * LEFT_RATIO);
            center = (int) Math.round(usable * CENTER_RATIO);
            right = usable - left - center;
        }
        if (left <= 0 || center <= 0 || right <= 0) {
            throw AppException.of("E_UI_LAYOUT",
                    "容器宽度 " + width + "px 下出现非正列宽，折叠组合不合法。");
        }
        return new ColumnSpec(width, left, center, right, collapseLeft, collapseRight);
    }

    /** 一次布局计算的不可变结果。 */
    public static final class ColumnSpec {
        private final int containerWidth;
        private final int left;
        private final int center;
        private final int right;
        private final boolean collapseLeft;
        private final boolean collapseRight;

        private ColumnSpec(int containerWidth, int left, int center, int right,
                           boolean collapseLeft, boolean collapseRight) {
            this.containerWidth = containerWidth;
            this.left = left;
            this.center = center;
            this.right = right;
            this.collapseLeft = collapseLeft;
            this.collapseRight = collapseRight;
        }

        public int getContainerWidth() { return containerWidth; }
        public int getLeft() { return left; }
        public int getCenter() { return center; }
        public int getRight() { return right; }
        public boolean isCollapseLeft() { return collapseLeft; }
        public boolean isCollapseRight() { return collapseRight; }

        /** 三栏宽 + 两条分隔条，恒等于容器宽（无溢出）。 */
        public int total() { return left + center + right + 2 * DIVIDER; }
        public boolean fits() { return total() <= containerWidth; }
    }
}
