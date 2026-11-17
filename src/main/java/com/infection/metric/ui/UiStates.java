package com.infection.metric.ui;

import com.infection.metric.common.AppException;

import java.util.EnumMap;
import java.util.Map;

/**
 * 树 / 详情 / 影响面表 / 差异说明四处区域的加载 / 空 / 错误三态纯逻辑模型。
 *
 * <p>{@link #stateContent(ViewArea, ViewState)} 返回文案、重试可用性、进度提示与取自 M8-A token 的配色，
 * 保证四处控件观感统一、不残留系统默认样式。区域状态独立保存，响应式布局切换（纯计算）不改动状态。</p>
 */
public final class UiStates {

    private final Map<ViewArea, ViewState> states = new EnumMap<>(ViewArea.class);

    /** 四处区域默认处于空态。 */
    public UiStates() {
        for (ViewArea a : ViewArea.values()) {
            states.put(a, ViewState.EMPTY);
        }
    }

    public void setState(ViewArea area, ViewState state) {
        if (area == null || state == null) {
            throw AppException.of("E_UI_STATE", "区域与视图状态不能为空。");
        }
        states.put(area, state);
    }

    public ViewState getState(ViewArea area) { return states.get(area); }

    /** 按区域与状态给出文案、重试可用性、进度提示与 token 配色。 */
    public StateContent stateContent(ViewArea area, ViewState state) {
        if (area == null || state == null) {
            throw AppException.of("E_UI_STATE", "区域与视图状态不能为空。");
        }
        return switch (state) {
            case LOADING -> new StateContent("正在加载" + area.getLabel() + "…",
                    false, true, UiTokens.SURFACE, UiTokens.TEXT_MUTED);
            case EMPTY -> new StateContent(emptyText(area),
                    false, false, UiTokens.SURFACE, UiTokens.TEXT_MUTED);
            case ERROR -> new StateContent(
                    area.getLabel() + "加载失败：上游数据未就绪或复算结果未落库，请点击重试。",
                    true, false, UiTokens.SURFACE, UiTokens.DANGER);
            case CONTENT -> new StateContent(area.getLabel() + "内容已就绪",
                    false, false, UiTokens.SURFACE, UiTokens.TEXT);
        };
    }

    /** 按区域当前状态取展示内容。 */
    public StateContent stateContent(ViewArea area) {
        return stateContent(area, getState(area));
    }

    private static String emptyText(ViewArea area) {
        return switch (area) {
            case LIBRARY_TREE -> "口径库暂无条目，请先导入上级口径包或新建口径。";
            case DETAIL -> "尚未选择口径，请在左侧选择一条查看详情。";
            case IMPACT_TABLE -> "该口径无下游依赖，无需重算。";
            case DIFF_PANEL -> "暂无差异说明，请先完成复算并落库。";
        };
    }
}
