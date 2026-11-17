package com.infection.metric.ui;

/** 某一交互态解析出的样式（纯数据，headless 安全），颜色均为 0xRRGGBB token 值。 */
public final class UiStateStyle {

    private final int background;
    private final int foreground;
    private final boolean enabled;
    private final boolean showProgress;
    private final String hint;

    public UiStateStyle(int background, int foreground, boolean enabled,
                        boolean showProgress, String hint) {
        this.background = background;
        this.foreground = foreground;
        this.enabled = enabled;
        this.showProgress = showProgress;
        this.hint = hint == null ? "" : hint;
    }

    public int getBackground() { return background; }
    public int getForeground() { return foreground; }
    public boolean isEnabled() { return enabled; }
    public boolean isShowProgress() { return showProgress; }
    public String getHint() { return hint; }
}
