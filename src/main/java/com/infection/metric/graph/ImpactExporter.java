package com.infection.metric.graph;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AtomicFiles;
import com.infection.metric.common.JsonSupport;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 《口径变更影响清单》导出器：JSON 与 CSV 两套，二者数据一致。
 *
 * <p>CSV 列名用中文业务名「受影响指标编码,指标名称,需重算季度,说明,来源」，便于 Excel 直接打开；
 * 数据行严格等于受影响指标数（表头之外）。需重算季度取交集，无交集写「无历史季度需重算」；
 * 空影响面时 JSON 结构完整（各字段齐全）且 {@code note} 说明明确。
 *
 * <p>人话说明：把条件级差异翻译成业务句子（如「分子新增排除条件：切口等级不等于 Ⅲ」），
 * 内部字段名 / 比较符经 {@link #FIELD_LABELS} / {@link #CMP_LABELS} 翻译，不堆黑话。
 */
public final class ImpactExporter {

    public static final String CSV_HEADER = "受影响指标编码,指标名称,需重算季度,说明,来源";

    /** 内部字段名 → 中文业务字段名。 */
    public static final Map<String, String> FIELD_LABELS = bizFields();

    /** 比较符 → 中文说法。 */
    public static final Map<String, String> CMP_LABELS = bizCmps();

    private static Map<String, String> bizFields() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("incision_grade", "切口等级");
        m.put("discharge_date", "出院日期");
        m.put("dept_code", "科室代码");
        m.put("primary_diagnosis_code", "主要诊断代码");
        m.put("surgery_code", "手术操作代码");
        m.put("infection_site", "感染部位");
        m.put("pathogen", "病原菌");
        m.put("mdro_flag", "多重耐药菌标志");
        m.put("quarter", "季度");
        return m;
    }

    private static Map<String, String> bizCmps() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("eq", "等于");
        m.put("ne", "不等于");
        m.put("in", "属于");
        m.put("not_in", "不属于");
        m.put("between", "介于");
        m.put("gte", "大于等于");
        m.put("lte", "小于等于");
        m.put("gt", "大于");
        m.put("lt", "小于");
        m.put("contains", "包含");
        return m;
    }

    private ImpactExporter() {}

    // ---------------- JSON ----------------

    public static String toJson(ImpactList list) { return JsonSupport.pretty(list.toJson()); }

    public static ObjectNode toJsonNode(ImpactList list) { return list.toJson(); }

    public static void exportJson(ImpactList list, Path path) { list.writeTo(path); }

    // ---------------- CSV ----------------

    /** CSV 文本：表头 + N 条数据行（N = 受影响指标数）。 */
    public static String toCsv(ImpactList list) {
        StringBuilder sb = new StringBuilder();
        sb.append(CSV_HEADER).append('\n');
        String quarters = list.recomputeQuartersText();
        for (ImpactList.AffectedIndicator a : list.getAffected()) {
            sb.append(escape(a.getCode())).append(',')
                    .append(escape(a.getName())).append(',')
                    .append(escape(quarters)).append(',')
                    .append(escape(a.getExplanation())).append(',')
                    .append(escape(a.getSource())).append('\n');
        }
        return sb.toString();
    }

    /** CSV 数据行数（不含表头）—— 等于受影响指标数。 */
    public static long csvDataRowCount(ImpactList list) {
        return list.getAffected().size();
    }

    public static void exportCsv(ImpactList list, Path path) {
        AtomicFiles.writeString(path, toCsv(list));
    }

    /** RFC4180 转义：含逗号、引号、换行时用双引号包裹并把内部引号翻倍。 */
    private static String escape(String s) {
        String v = s == null ? "" : s;
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    // ---------------- 人话说明 ----------------

    /**
     * 条件级差异 → 业务说明句。
     *
     * @param bucket     差异所属桶，如「分子」「分母」「排除条件」
     * @param action     动作，如「新增排除条件」「新增过滤条件」「移除条件」「修改取值」
     * @param field      内部字段名（经字典翻译成中文业务名）
     * @param cmp        比较符（经字典翻译成中文说法）
     * @param valueText  取值的人话文本
     */
    public static String humanize(String bucket, String action,
                                  String field, String cmp, String valueText) {
        String fieldLabel = FIELD_LABELS.getOrDefault(field, field);
        String cmpLabel = CMP_LABELS.getOrDefault(cmp, cmp);
        String tail = cmpLabel.isEmpty() ? valueText : fieldLabel + cmpLabel + " " + valueText;
        return bucket + action + "：" + tail;
    }

    /** 一条条件级差异（业务维度 + 动作 + 字段/比较符/取值），可渲染成人话说明。 */
    public static final class ConditionDiff {
        private final String bucket;
        private final String action;
        private final String field;
        private final String cmp;
        private final String valueText;

        public ConditionDiff(String bucket, String action, String field, String cmp, String valueText) {
            this.bucket = bucket;
            this.action = action;
            this.field = field;
            this.cmp = cmp;
            this.valueText = valueText;
        }

        public String getBucket() { return bucket; }
        public String getAction() { return action; }
        public String getField() { return field; }
        public String getCmp() { return cmp; }
        public String getValueText() { return valueText; }

        public String humanize() {
            return ImpactExporter.humanize(bucket, action, field, cmp, valueText);
        }
    }
}
