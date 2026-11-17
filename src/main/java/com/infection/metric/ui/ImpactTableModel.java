package com.infection.metric.ui;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * 影响面表纯逻辑模型：受影响指标（编码 + 名称 + 版本）、需重算历史季度、每条变更的人话说明与来源引用。
 *
 * <p>导出联动：{@link #exportJson()} / {@link #exportCsv()} 直接返回字符串，复用 Jackson 与自有
 * {@link ImpactRow}，不硬依赖 graph 包类；JSON 与 CSV 使用同一套中文业务列名，便于 Excel 直接打开。
 * 空态给出「该口径无下游依赖，无需重算」的明确引导，且导出结构保持完整（仅表头 + 说明）。</p>
 */
public final class ImpactTableModel {

    /** 空影响面引导文案。 */
    public static final String EMPTY_HINT = "该口径无下游依赖，无需重算";

    /** CSV / JSON 共用的中文业务列名。 */
    public static final List<String> CSV_COLUMNS = List.of(
            "受影响指标编码", "指标名称", "口径版本", "需重算季度", "人话说明", "来源引用", "深度");

    private final List<ImpactRow> rows;

    public ImpactTableModel(List<ImpactRow> rows) {
        this.rows = List.copyOf(rows == null ? List.of() : rows);
    }

    public boolean isEmpty() { return rows.isEmpty(); }
    public String emptyHint() { return EMPTY_HINT; }
    public List<String> columns() { return CSV_COLUMNS; }
    public List<ImpactRow> rows() { return rows; }

    /** 表格 / CSV 的单元格矩阵。 */
    public List<String[]> cells() {
        List<String[]> out = new ArrayList<>();
        for (ImpactRow r : rows) {
            out.add(new String[]{
                    r.getIndicatorCode(),
                    r.getIndicatorName(),
                    r.getVersion(),
                    String.join("、", r.getQuarters()),
                    r.getChangeNote(),
                    r.getSourceRef(),
                    String.valueOf(r.getDepth())});
        }
        return out;
    }

    /** 导出为 JSON 字符串（Jackson）。 */
    public String exportJson() {
        ObjectNode root = JsonSupport.object();
        root.put("说明", isEmpty() ? EMPTY_HINT : "共 " + rows.size() + " 条受影响指标需重算");
        root.put("受影响指标数", rows.size());
        ArrayNode arr = root.putArray("impacted_indicators");
        for (ImpactRow r : rows) {
            ObjectNode o = arr.addObject();
            o.put("受影响指标编码", r.getIndicatorCode());
            o.put("指标名称", r.getIndicatorName());
            o.put("口径版本", r.getVersion());
            ArrayNode q = o.putArray("需重算季度");
            for (String s : r.getQuarters()) {
                q.add(s);
            }
            o.put("人话说明", r.getChangeNote());
            o.put("来源引用", r.getSourceRef());
            o.put("深度", r.getDepth());
        }
        return JsonSupport.pretty(root);
    }

    /** 导出为 CSV 字符串：表头用中文业务名，行数 = 受影响指标数 + 1。 */
    public String exportCsv() {
        List<String> lines = new ArrayList<>();
        lines.add(String.join(",", CSV_COLUMNS));
        for (String[] row : cells()) {
            String[] escaped = new String[row.length];
            for (int i = 0; i < row.length; i++) {
                escaped[i] = csv(row[i]);
            }
            lines.add(String.join(",", escaped));
        }
        return String.join("\n", lines);
    }

    private static String csv(String value) {
        String s = value == null ? "" : value;
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
