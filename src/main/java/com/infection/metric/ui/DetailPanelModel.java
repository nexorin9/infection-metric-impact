package com.infection.metric.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 详情面板纯逻辑模型：选中口径后给出标题 / 分子 / 分母 / 排除条件 / 适用科室与期间 /
 * 数据源字段 / 生效期 / 状态 / 责任人 / 审批人 / 审批时间等字段，数字与来源同屏。
 *
 * <p>未选择口径时为空态、加载时给出进度提示；字段集合由 {@link #requiredLabels()} 统一定义，
 * 便于断言「详情字段齐全」。本类不创建任何 Swing 组件。</p>
 */
public final class DetailPanelModel {

    /** 未选中口径的空态引导。 */
    public static final String EMPTY_HINT = "尚未选择口径。请在左侧口径库选中一条口径查看详情。";
    /** 加载态进度提示。 */
    public static final String LOADING_HINT = "正在加载口径详情，请稍候…";

    private UiMetricItem selected;

    public void select(UiMetricItem item) { this.selected = item; }
    public void clear() { this.selected = null; }
    public boolean hasSelection() { return selected != null; }
    public UiMetricItem getSelected() { return selected; }
    public String emptyHint() { return EMPTY_HINT; }
    public String loadingHint() { return LOADING_HINT; }

    /** 详情面板必须展示的字段标签（顺序稳定）。 */
    public static List<String> requiredLabels() {
        return List.of("标题", "分子", "分母", "排除条件", "适用科室", "适用期间",
                "数据源字段", "生效期", "状态", "责任人", "审批人", "审批时间");
    }

    /** 当前选中口径的「标签 → 值」明细行；未选中时返回空列表。 */
    public List<DetailLine> lines() {
        if (selected == null) {
            return List.of();
        }
        List<DetailLine> out = new ArrayList<>();
        out.add(new DetailLine("标题", selected.getTitle()));
        out.add(new DetailLine("分子", selected.getNumeratorText()));
        out.add(new DetailLine("分母", selected.getDenominatorText()));
        out.add(new DetailLine("排除条件", selected.getExclusionsText()));
        out.add(new DetailLine("适用科室", join(selected.getDeptCodes())));
        out.add(new DetailLine("适用期间", join(selected.getPeriods())));
        out.add(new DetailLine("数据源字段", join(selected.getDataSourceFields())));
        out.add(new DetailLine("生效期", periodRange()));
        out.add(new DetailLine("状态", selected.getStatus().name()));
        out.add(new DetailLine("责任人", selected.getAuthor()));
        out.add(new DetailLine("审批人", selected.getApprover()));
        out.add(new DetailLine("审批时间", selected.getApprovedAt()));
        return out;
    }

    /** 字段齐全且值非空才允许渲染详情（避免半截面板）。 */
    public boolean linesComplete() {
        if (!hasSelection()) {
            return false;
        }
        Set<String> present = new LinkedHashSet<>();
        for (DetailLine line : lines()) {
            if (line.getValue() != null && !line.getValue().isBlank()) {
                present.add(line.getLabel());
            }
        }
        return present.containsAll(requiredLabels());
    }

    private String periodRange() {
        String from = selected.getEffectiveFrom();
        String to = selected.getEffectiveTo();
        if ((from == null || from.isBlank()) && (to == null || to.isBlank())) {
            return "长期有效";
        }
        return from + " ~ " + to;
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "（无）";
        }
        return String.join("、", values);
    }

    /** 一条「标签 → 值」明细行。 */
    public static final class DetailLine {
        private final String label;
        private final String value;
        public DetailLine(String label, String value) {
            this.label = label;
            this.value = value == null ? "" : value;
        }
        public String getLabel() { return label; }
        public String getValue() { return value; }
    }
}
