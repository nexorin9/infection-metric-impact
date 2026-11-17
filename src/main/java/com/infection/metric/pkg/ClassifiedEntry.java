package com.infection.metric.pkg;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.util.List;

/**
 * 三清单中的一条分类结果：带清单类别、条目标识、依据来源与条件级差异。
 *
 * <p>「依据来源」是主任敢不敢采纳结论的根据，每条必填；实质口径变更条目
 * 额外携带条件级差异（分子 / 分母逐片叶子的增删），可直接贴进差异说明。</p>
 */
public final class ClassifiedEntry {

    /** 清单类别。 */
    public enum Category {
        NEW("新增"),
        CHANGED("实质口径变更"),
        LOCAL_EXCEPTION("本院例外"),
        MANUAL_REVIEW("待人工判定"),
        UNCHANGED("无实质变更");

        private final String label;
        Category(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final Category category;
    private final String entryNo;
    private final String indicatorCode;
    private final String indicatorName;
    private final String basisSource;
    private final List<String> conditionDiffs;
    private final String localVersion;
    private final String note;

    public ClassifiedEntry(Category category, String entryNo, String indicatorCode,
                           String indicatorName, String basisSource,
                           List<String> conditionDiffs, String localVersion, String note) {
        this.category = category;
        this.entryNo = entryNo == null ? "" : entryNo;
        this.indicatorCode = indicatorCode == null ? "" : indicatorCode;
        this.indicatorName = indicatorName == null ? "" : indicatorName;
        this.basisSource = basisSource == null ? "" : basisSource;
        this.conditionDiffs = conditionDiffs == null ? List.of() : List.copyOf(conditionDiffs);
        this.localVersion = localVersion == null ? "" : localVersion;
        this.note = note == null ? "" : note;
    }

    public Category getCategory() { return category; }
    public String getEntryNo() { return entryNo; }
    public String getIndicatorCode() { return indicatorCode; }
    public String getIndicatorName() { return indicatorName; }
    public String getBasisSource() { return basisSource; }
    public List<String> getConditionDiffs() { return conditionDiffs; }
    public String getLocalVersion() { return localVersion; }
    public String getNote() { return note; }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("category", category.name());
        o.put("category_label", category.label());
        o.put("entry_no", entryNo);
        o.put("indicator_code", indicatorCode);
        o.put("indicator_name", indicatorName);
        o.put("basis_source", basisSource);
        o.put("local_version", localVersion);
        o.put("note", note);
        ArrayNode diffs = o.putArray("condition_diffs");
        for (String d : conditionDiffs) diffs.add(d);
        return o;
    }
}
