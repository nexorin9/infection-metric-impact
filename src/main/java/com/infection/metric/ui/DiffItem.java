package com.infection.metric.ui;

import com.infection.metric.common.AppException;

/** 一条可逐条确认的差异：所属段、数字 / 说明、来源引用、确认状态与填写意见。 */
public final class DiffItem {

    private final String id;
    private final DiffSectionType sectionType;
    private final String text;
    private final String sourceRef;
    private ConfirmState confirmState = ConfirmState.UNSET;
    private String note = "";

    public DiffItem(String id, DiffSectionType sectionType, String text, String sourceRef) {
        this.id = id;
        this.sectionType = sectionType;
        this.text = text == null ? "" : text;
        this.sourceRef = sourceRef == null ? "" : sourceRef;
    }

    public String getId() { return id; }
    public DiffSectionType getSectionType() { return sectionType; }
    public String getText() { return text; }
    public String getSourceRef() { return sourceRef; }
    public ConfirmState getConfirmState() { return confirmState; }
    public String getNote() { return note; }

    /** 标为「已确认 / 有疑问 / 撤销」并填写意见。 */
    public void confirm(ConfirmState state, String note) {
        if (state == null) {
            throw AppException.of("E_UI_DIFF", "确认状态不能为空。");
        }
        this.confirmState = state;
        this.note = note == null ? "" : note;
    }

    /** 只有「已确认」算已确认；「有疑问」与「未确认」都计入未确认。 */
    public boolean isConfirmed() { return confirmState == ConfirmState.CONFIRMED; }
}
