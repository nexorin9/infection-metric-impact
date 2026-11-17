package com.infection.metric.ui;

/** 界面上的动作：发布 / 停用 / 删除本地草稿为破坏性操作，查看详情为非破坏性反例。 */
public enum DestructiveAction {

    /** 发布写回院内质控系统。 */
    PUBLISH("发布"),
    /** 停用口径（RETIRED）。 */
    RETIRE("停用"),
    /** 删除本地草稿。 */
    DELETE_DRAFT("删除本地草稿"),
    /** 仅查看详情，不改动数据。 */
    OPEN_DETAIL("查看详情");

    private final String label;

    DestructiveAction(String label) { this.label = label; }

    public String getLabel() { return label; }
}
