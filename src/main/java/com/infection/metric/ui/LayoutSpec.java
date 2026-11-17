package com.infection.metric.ui;

import java.util.List;

/** 一次响应式布局计算的不可变结果：模式、并列列宽、抽屉标志与宽度分配总和。 */
public final class LayoutSpec {

    private final int containerWidth;
    private final String mode;
    private final int left;
    private final int center;
    private final int right;
    private final boolean drawerVisible;
    private final int dividerCount;
    private final int divider;

    public LayoutSpec(int containerWidth, String mode, int left, int center, int right,
                      boolean drawerVisible, int dividerCount, int divider) {
        this.containerWidth = containerWidth;
        this.mode = mode;
        this.left = left;
        this.center = center;
        this.right = right;
        this.drawerVisible = drawerVisible;
        this.dividerCount = dividerCount;
        this.divider = divider;
    }

    public int getContainerWidth() { return containerWidth; }
    public String getMode() { return mode; }
    public int getLeft() { return left; }
    public int getCenter() { return center; }
    public int getRight() { return right; }
    public boolean isDrawerVisible() { return drawerVisible; }
    public int getDividerCount() { return dividerCount; }
    public int getDivider() { return divider; }

    /** 并列显示的列宽：三栏为左/中/右；两栏时右栏进抽屉不占并列宽。 */
    public List<Integer> getColumnWidths() {
        return right > 0 ? List.of(left, center, right) : List.of(left, center);
    }

    /** 并列列宽之和 + 分隔条，恒等于容器宽（无溢出）。 */
    public int total() {
        int sum = 0;
        for (int w : getColumnWidths()) {
            sum += w;
        }
        return sum + dividerCount * divider;
    }

    public boolean fits() { return total() <= containerWidth; }
}
