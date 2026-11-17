package com.infection.metric.ui;

import com.infection.metric.common.AppException;

/** 操作反馈纯逻辑：提交复核 / 发布 / 导出等操作的进行中、成功、失败提示，长任务显示进度。 */
public final class OperationFeedback {

    private OperationFeedback() {}

    /** 操作反馈文案：进行中 / 成功 / 失败（失败指向运行摘要）。 */
    public static String textFor(String operation, FeedbackState state) {
        if (state == null) {
            throw AppException.of("E_UI_FEEDBACK", "反馈状态不能为空。");
        }
        String op = operation == null || operation.isBlank() ? "操作" : operation;
        return switch (state) {
            case RUNNING -> "正在" + op + "…";
            case SUCCESS -> op + "成功。";
            case FAILURE -> op + "失败：请查看运行摘要中的可读原因后重试。";
        };
    }

    /** 长任务进度：已完成 / 总数 / 百分比 / 是否显示进度条。 */
    public static Progress progress(int done, int total) {
        if (total <= 0 || done < 0 || done > total) {
            throw AppException.of("E_UI_FEEDBACK",
                    "进度参数不合法：done=" + done + ", total=" + total);
        }
        int percent = (int) Math.round(done * 100.0 / total);
        String text = "已完成 " + done + "/" + total + "（" + percent + "%）";
        return new Progress(done, total, percent, text, true);
    }

    /** 长任务进度快照。 */
    public static final class Progress {
        private final int done;
        private final int total;
        private final int percent;
        private final String text;
        private final boolean showBar;
        public Progress(int done, int total, int percent, String text, boolean showBar) {
            this.done = done;
            this.total = total;
            this.percent = percent;
            this.text = text;
            this.showBar = showBar;
        }
        public int getDone() { return done; }
        public int getTotal() { return total; }
        public int getPercent() { return percent; }
        public String getText() { return text; }
        public boolean isShowBar() { return showBar; }
    }
}
