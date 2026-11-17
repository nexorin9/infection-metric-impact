package com.infection.metric.pkg;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * 口径包三清单结果：新增 / 实质口径变更 / 本院例外，另附「待人工判定」与
 * 「无实质变更」两组，保证逐条有归属、不丢条目。
 *
 * <p>每条 {@link ClassifiedEntry} 都带依据来源；支持导出 JSON 与 CSV 两套，
 * CSV 用中文业务列名，每条都带依据来源列，便于 Excel 直接打开核对。</p>
 */
public final class ThreeLists {

    private final String packageVersion;
    private final List<ClassifiedEntry> newItems;
    private final List<ClassifiedEntry> changedItems;
    private final List<ClassifiedEntry> localExceptions;
    private final List<ClassifiedEntry> manualReview;
    private final List<ClassifiedEntry> unchanged;

    public ThreeLists(String packageVersion,
                      List<ClassifiedEntry> newItems,
                      List<ClassifiedEntry> changedItems,
                      List<ClassifiedEntry> localExceptions,
                      List<ClassifiedEntry> manualReview,
                      List<ClassifiedEntry> unchanged) {
        this.packageVersion = packageVersion == null ? "" : packageVersion;
        this.newItems = copy(newItems);
        this.changedItems = copy(changedItems);
        this.localExceptions = copy(localExceptions);
        this.manualReview = copy(manualReview);
        this.unchanged = copy(unchanged);
    }

    private static List<ClassifiedEntry> copy(List<ClassifiedEntry> src) {
        return src == null ? List.of() : List.copyOf(src);
    }

    public String getPackageVersion() { return packageVersion; }
    public List<ClassifiedEntry> getNewItems() { return newItems; }
    public List<ClassifiedEntry> getChangedItems() { return changedItems; }
    public List<ClassifiedEntry> getLocalExceptions() { return localExceptions; }
    public List<ClassifiedEntry> getManualReview() { return manualReview; }
    public List<ClassifiedEntry> getUnchanged() { return unchanged; }

    /** 全部条目（按清单顺序拼接，便于统一导出与计数）。 */
    public List<ClassifiedEntry> all() {
        List<ClassifiedEntry> out = new ArrayList<>();
        out.addAll(newItems);
        out.addAll(changedItems);
        out.addAll(localExceptions);
        out.addAll(manualReview);
        out.addAll(unchanged);
        return List.copyOf(out);
    }

    public int total() { return all().size(); }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("package_version", packageVersion);
        o.put("new_count", newItems.size());
        o.put("changed_count", changedItems.size());
        o.put("local_exception_count", localExceptions.size());
        o.put("manual_review_count", manualReview.size());
        o.put("unchanged_count", unchanged.size());
        o.set("新增", listNode(newItems));
        o.set("实质口径变更", listNode(changedItems));
        o.set("本院例外", listNode(localExceptions));
        o.set("待人工判定", listNode(manualReview));
        o.set("无实质变更", listNode(unchanged));
        return o;
    }

    private static ArrayNode listNode(List<ClassifiedEntry> list) {
        ArrayNode arr = JsonSupport.array();
        for (ClassifiedEntry e : list) arr.add(e.toJson());
        return arr;
    }

    public String exportJson() { return JsonSupport.pretty(toJson()); }

    /** CSV 导出：每条都带「依据来源」列；含中文表头。 */
    public String exportCsv() { return toCsv(); }

    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("清单类别,条目编号,指标编码,指标名称,依据来源,本院对应版本,条件级差异,说明\n");
        for (ClassifiedEntry e : all()) {
            sb.append(csv(e.getCategory().label())).append(',')
                    .append(csv(e.getEntryNo())).append(',')
                    .append(csv(e.getIndicatorCode())).append(',')
                    .append(csv(e.getIndicatorName())).append(',')
                    .append(csv(e.getBasisSource())).append(',')
                    .append(csv(e.getLocalVersion())).append(',')
                    .append(csv(String.join(" / ", e.getConditionDiffs()))).append(',')
                    .append(csv(e.getNote())).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String raw) {
        String s = raw == null ? "" : raw;
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
