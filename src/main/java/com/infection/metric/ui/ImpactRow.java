package com.infection.metric.ui;

import java.util.List;

/** 影响面表的一行：受影响指标 + 需重算历史季度 + 人话变更说明 + 来源引用 + 深度。 */
public final class ImpactRow {

    private final String indicatorCode;
    private final String indicatorName;
    private final String version;
    private final List<String> quarters;
    private final String changeNote;
    private final String sourceRef;
    private final int depth;

    public ImpactRow(String indicatorCode, String indicatorName, String version,
                     List<String> quarters, String changeNote, String sourceRef, int depth) {
        this.indicatorCode = indicatorCode == null ? "" : indicatorCode;
        this.indicatorName = indicatorName == null ? "" : indicatorName;
        this.version = version == null ? "" : version;
        this.quarters = quarters == null ? List.of() : List.copyOf(quarters);
        this.changeNote = changeNote == null ? "" : changeNote;
        this.sourceRef = sourceRef == null ? "" : sourceRef;
        this.depth = depth;
    }

    public String getIndicatorCode() { return indicatorCode; }
    public String getIndicatorName() { return indicatorName; }
    public String getVersion() { return version; }
    public List<String> getQuarters() { return quarters; }
    public String getChangeNote() { return changeNote; }
    public String getSourceRef() { return sourceRef; }
    public int getDepth() { return depth; }
}
