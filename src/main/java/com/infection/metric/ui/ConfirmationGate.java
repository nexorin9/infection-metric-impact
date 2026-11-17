package com.infection.metric.ui;

import com.infection.metric.common.AppException;

import java.util.List;

/**
 * 破坏性操作二次确认门禁（纯逻辑，不弹真实对话框）。
 *
 * <p>发布、停用（RETIRED）、删除本地草稿三类动作必须二次确认，且确认文案写明将影响哪些指标
 * 与哪些季度。二次确认由界面层在真正执行动作前展示；本类只提供「是否需要」与「文案内容」的判断。</p>
 */
public final class ConfirmationGate {

    private ConfirmationGate() {}

    /** 发布 / 停用 / 删除本地草稿需二次确认；查看详情等非破坏性动作不需要。 */
    public static boolean requiresSecondConfirmation(DestructiveAction action) {
        return action == DestructiveAction.PUBLISH
                || action == DestructiveAction.RETIRE
                || action == DestructiveAction.DELETE_DRAFT;
    }

    /** 二次确认文案：动作 + 将影响的指标 + 涉及的季度 + 后果，供界面弹窗展示。 */
    public static String confirmationText(DestructiveAction action,
                                          List<String> indicators, List<String> quarters) {
        if (action == null) {
            throw AppException.of("E_UI_PUBLISH", "动作不能为空，无法生成二次确认文案。");
        }
        String ind = (indicators == null || indicators.isEmpty()) ? "（无）" : String.join("、", indicators);
        String q = (quarters == null || quarters.isEmpty()) ? "（无历史季度）" : String.join("、", quarters);
        String effect = switch (action) {
            case PUBLISH -> "将写回院内质控系统并留回执";
            case RETIRE -> "将停用该口径，复用它的指标影响面需重新确认";
            case DELETE_DRAFT -> "将删除本地草稿，操作不可恢复";
            case OPEN_DETAIL -> "仅查看，不改动任何数据";
        };
        String tail = requiresSecondConfirmation(action)
                ? "此操作需二次确认。" : "此操作无需二次确认。";
        return "确认「" + action.getLabel() + "」？将影响指标：" + ind
                + "；涉及季度：" + q + "。" + effect + "。" + tail;
    }
}
