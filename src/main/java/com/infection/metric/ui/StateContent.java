package com.infection.metric.ui;

/** 某一区域某一状态要展示的内容：文案、是否可重试、是否显示进度，以及取自 token 的配色。 */
public final class StateContent {

    private final String text;
    private final boolean retryAvailable;
    private final boolean showProgress;
    private final int background;
    private final int foreground;

    public StateContent(String text, boolean retryAvailable, boolean showProgress,
                        int background, int foreground) {
        this.text = text == null ? "" : text;
        this.retryAvailable = retryAvailable;
        this.showProgress = showProgress;
        this.background = background;
        this.foreground = foreground;
    }

    public String getText() { return text; }
    public boolean isRetryAvailable() { return retryAvailable; }
    public boolean isShowProgress() { return showProgress; }
    public int getBackground() { return background; }
    public int getForeground() { return foreground; }
}
