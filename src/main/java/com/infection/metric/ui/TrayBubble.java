package com.infection.metric.ui;

/** 待复核气泡：是否弹出、提示文案（含待办条数）、点击直达目标与待办条数。 */
public final class TrayBubble {

    private final boolean shown;
    private final String text;
    private final String target;
    private final int pendingCount;

    public TrayBubble(boolean shown, String text, String target, int pendingCount) {
        this.shown = shown;
        this.text = text;
        this.target = target;
        this.pendingCount = pendingCount;
    }

    public boolean isShown() { return shown; }
    public String getText() { return text; }
    public String getTarget() { return target; }
    public int getPendingCount() { return pendingCount; }
}
