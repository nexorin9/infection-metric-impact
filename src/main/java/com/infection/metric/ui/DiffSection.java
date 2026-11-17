package com.infection.metric.ui;

import java.util.List;

/** 差异说明的一段：类型、数字、来源引用，以及该段下可逐条确认的差异条目。 */
public final class DiffSection {

    private final DiffSectionType type;
    private final String number;
    private final String sourceRef;
    private final List<DiffItem> items;

    public DiffSection(DiffSectionType type, String number, String sourceRef, List<DiffItem> items) {
        this.type = type;
        this.number = number == null ? "" : number;
        this.sourceRef = sourceRef == null ? "" : sourceRef;
        this.items = List.copyOf(items == null ? List.of() : items);
    }

    public DiffSectionType getType() { return type; }
    public String getNumber() { return number; }
    public String getSourceRef() { return sourceRef; }
    public List<DiffItem> getItems() { return items; }
}
