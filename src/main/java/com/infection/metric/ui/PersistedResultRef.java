package com.infection.metric.ui;

/**
 * 已落库复算结果的引用：运行标识 + 季度 + 口径版本 + 是否确已落库。
 *
 * <p>状态一致性要求：差异说明面板显示的数字必须来自已落库的复算结果。
 * 构造 {@link DiffPanelModel} 时传入本引用，标识为空或未落库时面板拒绝显示。</p>
 */
public final class PersistedResultRef {

    private final String resultId;
    private final String quarter;
    private final String definitionVersion;
    private final boolean persisted;

    public PersistedResultRef(String resultId, String quarter,
                              String definitionVersion, boolean persisted) {
        this.resultId = resultId == null ? "" : resultId;
        this.quarter = quarter == null ? "" : quarter;
        this.definitionVersion = definitionVersion == null ? "" : definitionVersion;
        this.persisted = persisted;
    }

    public String getResultId() { return resultId; }
    public String getQuarter() { return quarter; }
    public String getDefinitionVersion() { return definitionVersion; }
    public boolean isPersisted() { return persisted; }

    /** 仅当确已落库且运行标识非空时才允许显示。 */
    public boolean isDisplayable() {
        return persisted && !resultId.isBlank();
    }
}
