package com.infection.metric.ui;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 差异说明面板纯逻辑模型：分子差 / 分母差 / 口径差三段展示 + 逐条确认 + 落库一致性门禁。
 *
 * <p>三段各带数字与来源引用同屏；每条差异可标「已确认 / 有疑问」并填意见，
 * 存在未确认差异（含有疑问）时 {@link #canSubmitReview()} 为 false 并阻止提交复核。</p>
 *
 * <p>状态一致性：面板显示的差异说明必须来自已落库的复算结果。构造时传入
 * {@link PersistedResultRef}，标识为空或未落库时 {@link #displaySections()} /
 * {@link #recomputeHint()} 一律抛 AppException，拒绝界面临时另算一套。</p>
 */
public final class DiffPanelModel {

    private final PersistedResultRef resultRef;
    private final Map<DiffSectionType, DiffSection> sections;
    private final List<DiffItem> allItems;

    /** 构造：校验三段齐全；落库一致性延迟到「显示 / 提交」时校验，便于表示待落库状态。 */
    public DiffPanelModel(PersistedResultRef resultRef, List<DiffSection> sectionList) {
        this.resultRef = resultRef;
        this.sections = new EnumMap<>(DiffSectionType.class);
        List<DiffSection> src = sectionList == null ? List.of() : sectionList;
        for (DiffSection s : src) {
            sections.put(s.getType(), s);
        }
        List<String> missing = new ArrayList<>();
        for (DiffSectionType t : DiffSectionType.values()) {
            if (!sections.containsKey(t)) {
                missing.add(t.getLabel());
            }
        }
        if (!missing.isEmpty()) {
            throw AppException.of("E_UI_DIFF",
                    "差异说明三段不齐，缺：" + String.join("、", missing) + "；不允许留空段。");
        }
        List<DiffItem> items = new ArrayList<>();
        for (DiffSection s : sections.values()) {
            items.addAll(s.getItems());
        }
        this.allItems = List.copyOf(items);
    }

    /** 是否来自已落库结果（标识非空且确已落库）。 */
    public boolean isDisplayable() {
        return resultRef != null && resultRef.isDisplayable();
    }

    /** 落库一致性门禁：未落库 / 无标识时拒绝显示。 */
    public void assertDisplayable() {
        if (!isDisplayable()) {
            throw ErrorCodes.error(ErrorCodes.E_AUDIT_001,
                    "reason", "差异说明必须来自已落库的复算结果，当前运行标识为空或结果尚未落库");
        }
    }

    /** 三段展示（固定顺序），显示前先过落库门禁。 */
    public List<DiffSection> displaySections() {
        assertDisplayable();
        return List.of(
                sections.get(DiffSectionType.NUMERATOR),
                sections.get(DiffSectionType.DENOMINATOR),
                sections.get(DiffSectionType.DEFINITION));
    }

    public List<String> sectionLabels() {
        return List.of(DiffSectionType.NUMERATOR.getLabel(),
                DiffSectionType.DENOMINATOR.getLabel(),
                DiffSectionType.DEFINITION.getLabel());
    }

    public boolean threeSectionsComplete() { return sections.size() == 3; }

    public List<DiffItem> items() { return allItems; }
    public int totalCount() { return allItems.size(); }

    /** 逐条确认：按条目 id 标记状态并填写意见。 */
    public void confirm(String itemId, ConfirmState state, String note) {
        for (DiffItem it : allItems) {
            if (it.getId().equals(itemId)) {
                it.confirm(state, note);
                return;
            }
        }
        throw AppException.of("E_UI_DIFF", "未知差异条目：" + itemId);
    }

    /** 未确认条目数：UNSET 与 QUESTIONED 都计入。 */
    public int unconfirmedCount() {
        return (int) allItems.stream().filter(it -> !it.isConfirmed()).count();
    }

    /** 存在未确认差异或结果未落库时，一律阻止提交复核。 */
    public boolean canSubmitReview() {
        return isDisplayable() && unconfirmedCount() == 0 && !allItems.isEmpty();
    }

    /** 提交复核动作：未逐条确认时显式抛错，不静默放行。 */
    public void submitReview() {
        if (!isDisplayable()) {
            assertDisplayable();
        }
        if (unconfirmedCount() > 0) {
            throw ErrorCodes.error(ErrorCodes.E_AUDIT_001,
                    "reason", "仍有 " + unconfirmedCount() + " 条差异未确认，不能提交复核");
        }
    }

    /** 数字可复算提示：写明来自哪次复算运行（运行标识 + 季度 + 口径版本）。 */
    public String recomputeHint() {
        assertDisplayable();
        return "本面板数字来自复算运行 " + resultRef.getResultId()
                + "（季度 " + resultRef.getQuarter()
                + "，口径版本 " + resultRef.getDefinitionVersion()
                + "），可用同一输入再次复算核对。";
    }
}
