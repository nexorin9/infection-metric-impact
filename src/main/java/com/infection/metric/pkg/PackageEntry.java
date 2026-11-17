package com.infection.metric.pkg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.expr.Canonicalizer;
import com.infection.metric.expr.ConditionTree;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 上级口径包内的单条口径条目模型。
 *
 * <p>字段：条目编号、指标编码、指标名称、口径定义、分子、分母、适用科室、
 * 生效期（起始 / 结束）。分子 / 分母既保留原始文本（CSV 常见），也支持携带
 * 结构化 {@link ConditionTree}（JSON 包可给条件树对象），供后续三清单分类
 * 复用 {@link Canonicalizer#fingerprint(ConditionTree)} 做实质口径比对。</p>
 *
 * <p>缺必需字段的条目被标记为「不完整」并通过 {@link #missingFields()} 列出
 * 缺失项，绝不静默丢弃——不完整条目仍完整保留在解析结果中并参与计数。</p>
 */
public final class PackageEntry {

    /** 判定完整性时逐项检查的必需字段（适用科室、生效结束期为可选）。 */
    public static final List<String> REQUIRED_FIELDS = List.of(
            "条目编号", "指标编码", "指标名称", "口径定义", "分子", "分母", "生效期");

    private final String entryNo;
    private final String indicatorCode;
    private final String indicatorName;
    private final String definition;
    private final String numerator;
    private final String denominator;
    private final ConditionTree numeratorTree;
    private final ConditionTree denominatorTree;
    private final List<String> deptCodes;
    private final String effectiveFrom;
    private final String effectiveTo;

    private PackageEntry(Builder b) {
        this.entryNo = nullToEmpty(b.entryNo);
        this.indicatorCode = nullToEmpty(b.indicatorCode);
        this.indicatorName = nullToEmpty(b.indicatorName);
        this.definition = nullToEmpty(b.definition);
        this.numerator = nullToEmpty(b.numerator);
        this.denominator = nullToEmpty(b.denominator);
        this.numeratorTree = b.numeratorTree;
        this.denominatorTree = b.denominatorTree;
        this.deptCodes = b.deptCodes == null ? List.of()
                : List.copyOf(new ArrayList<>(new LinkedHashSet<>(b.deptCodes)));
        this.effectiveFrom = nullToEmpty(b.effectiveFrom);
        this.effectiveTo = nullToEmpty(b.effectiveTo);
    }

    public static Builder builder() { return new Builder(); }

    public String getEntryNo() { return entryNo; }
    public String getIndicatorCode() { return indicatorCode; }
    public String getIndicatorName() { return indicatorName; }
    public String getDefinition() { return definition; }
    public String getNumerator() { return numerator; }
    public String getDenominator() { return denominator; }
    public ConditionTree getNumeratorTree() { return numeratorTree; }
    public ConditionTree getDenominatorTree() { return denominatorTree; }
    public List<String> getDeptCodes() { return deptCodes; }
    public String getEffectiveFrom() { return effectiveFrom; }
    public String getEffectiveTo() { return effectiveTo; }

    /** 缺失的必需字段清单（中文业务名，顺序稳定）。 */
    public List<String> missingFields() {
        List<String> out = new ArrayList<>();
        if (entryNo.isBlank()) out.add("条目编号");
        if (indicatorCode.isBlank()) out.add("指标编码");
        if (indicatorName.isBlank()) out.add("指标名称");
        if (definition.isBlank()) out.add("口径定义");
        if (numerator.isBlank() && numeratorTree == null) out.add("分子");
        if (denominator.isBlank() && denominatorTree == null) out.add("分母");
        if (effectiveFrom.isBlank()) out.add("生效期");
        return out;
    }

    public boolean isComplete() { return missingFields().isEmpty(); }

    /** 分子口径指纹：有结构化条件树则复用 canonical form 指纹，否则用规范化文本哈希。 */
    public String numeratorFingerprint() { return fingerprintOf(numeratorTree, numerator); }

    /** 分母口径指纹：规则同 {@link #numeratorFingerprint()}。 */
    public String denominatorFingerprint() { return fingerprintOf(denominatorTree, denominator); }

    /** 整条口径的内容指纹（分子 + 分母组合）。 */
    public String fingerprint() {
        return Hashes.sha256Of(numeratorFingerprint(), denominatorFingerprint());
    }

    /** 分子条件级条目（每片叶子的 canonical 形式，带「分子:」前缀），供差异引擎逐条比对。 */
    public List<String> numeratorLeafForms() { return leafForms(numeratorTree, numerator, "分子"); }

    /** 分母条件级条目，规则同 {@link #numeratorLeafForms()}。 */
    public List<String> denominatorLeafForms() { return leafForms(denominatorTree, denominator, "分母"); }

    private static String fingerprintOf(ConditionTree tree, String text) {
        if (tree != null) return Canonicalizer.fingerprint(tree);
        return Hashes.sha256(normalize(text));
    }

    private static List<String> leafForms(ConditionTree tree, String text, String tag) {
        List<String> out = new ArrayList<>();
        if (tree != null) {
            for (ConditionTree leaf : tree.leaves()) {
                out.add(tag + ":" + Canonicalizer.canonicalNode(leaf).toString());
            }
        } else {
            out.add(tag + ":" + normalize(text));
        }
        return out;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    /** 可序列化中间结构：文本字段、可选条件树、完整性自描述全部保留。 */
    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("entry_no", entryNo);
        o.put("indicator_code", indicatorCode);
        o.put("indicator_name", indicatorName);
        o.put("definition", definition);
        o.put("numerator", numerator);
        o.put("denominator", denominator);
        if (numeratorTree != null) o.set("numerator_tree", numeratorTree.toJson());
        if (denominatorTree != null) o.set("denominator_tree", denominatorTree.toJson());
        ArrayNode depts = o.putArray("dept_codes");
        for (String d : deptCodes) depts.add(d);
        o.put("effective_from", effectiveFrom);
        o.put("effective_to", effectiveTo);
        o.put("complete", isComplete());
        ArrayNode missing = o.putArray("missing_fields");
        for (String m : missingFields()) missing.add(m);
        return o;
    }

    public static PackageEntry fromJson(JsonNode n) {
        Builder b = builder()
                .entryNo(n.path("entry_no").asText(""))
                .indicatorCode(n.path("indicator_code").asText(""))
                .indicatorName(n.path("indicator_name").asText(""))
                .definition(n.path("definition").asText(""))
                .deptCodes(JsonSupport.stringList(n.path("dept_codes")))
                .effectiveFrom(n.path("effective_from").asText(""))
                .effectiveTo(n.path("effective_to").asText(""));
        JsonNode num = n.path("numerator");
        if (num.isObject()) {
            b.numeratorTree(ConditionTree.fromJson(num));
            b.numerator(num.toString());
        } else {
            b.numerator(num.asText(""));
        }
        JsonNode den = n.path("denominator");
        if (den.isObject()) {
            b.denominatorTree(ConditionTree.fromJson(den));
            b.denominator(den.toString());
        } else {
            b.denominator(den.asText(""));
        }
        if (n.has("numerator_tree")) b.numeratorTree(ConditionTree.fromJson(n.path("numerator_tree")));
        if (n.has("denominator_tree")) b.denominatorTree(ConditionTree.fromJson(n.path("denominator_tree")));
        return b.build();
    }

    public static final class Builder {
        private String entryNo = "", indicatorCode = "", indicatorName = "", definition = "";
        private String numerator = "", denominator = "";
        private ConditionTree numeratorTree, denominatorTree;
        private List<String> deptCodes = new ArrayList<>();
        private String effectiveFrom = "", effectiveTo = "";

        public Builder entryNo(String v) { this.entryNo = v; return this; }
        public Builder indicatorCode(String v) { this.indicatorCode = v; return this; }
        public Builder indicatorName(String v) { this.indicatorName = v; return this; }
        public Builder definition(String v) { this.definition = v; return this; }
        public Builder numerator(String v) { this.numerator = v; return this; }
        public Builder denominator(String v) { this.denominator = v; return this; }
        public Builder numeratorTree(ConditionTree v) { this.numeratorTree = v; return this; }
        public Builder denominatorTree(ConditionTree v) { this.denominatorTree = v; return this; }
        public Builder deptCodes(List<String> v) { this.deptCodes = new ArrayList<>(v); return this; }
        public Builder effectiveFrom(String v) { this.effectiveFrom = v; return this; }
        public Builder effectiveTo(String v) { this.effectiveTo = v; return this; }
        public PackageEntry build() { return new PackageEntry(this); }
    }
}
