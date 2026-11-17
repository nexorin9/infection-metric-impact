package com.infection.metric.metriclib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.expr.Canonicalizer;
import com.infection.metric.expr.ConditionTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 口径条目：本产品唯一的权威定义载体。
 * 联合主键 = indicator_code + definition_version；同一指标多版本并存。
 */
public final class MetricDefinition {

    private final String indicatorCode;
    private final String definitionVersion;
    private final String title;
    private final ConditionTree numerator;
    private final ConditionTree denominator;
    private final List<ConditionTree> exclusions;
    private final List<String> deptCodes;
    private final List<String> periods;
    private final List<DataSourceRef> dataSources;
    private final String effectiveFrom;
    private final String effectiveTo;
    private final MetricStatus status;
    private final String author;
    private final String approver;
    private final String approvedAt;
    private final String dictionaryVersion;

    public MetricDefinition(String indicatorCode, String definitionVersion, String title,
                            ConditionTree numerator, ConditionTree denominator,
                            List<ConditionTree> exclusions,
                            List<String> deptCodes, List<String> periods,
                            List<DataSourceRef> dataSources,
                            String effectiveFrom, String effectiveTo,
                            MetricStatus status, String author, String approver,
                            String approvedAt, String dictionaryVersion) {
        this.indicatorCode = Objects.requireNonNull(indicatorCode, "indicator_code");
        this.definitionVersion = Objects.requireNonNull(definitionVersion, "definition_version");
        this.title = title == null ? "" : title;
        this.numerator = numerator;
        this.denominator = denominator;
        this.exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        this.deptCodes = deptCodes == null ? List.of() : List.copyOf(deptCodes);
        this.periods = periods == null ? List.of() : List.copyOf(periods);
        this.dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
        this.effectiveFrom = effectiveFrom == null ? "" : effectiveFrom;
        this.effectiveTo = effectiveTo == null ? "" : effectiveTo;
        this.status = status == null ? MetricStatus.DRAFT : status;
        this.author = author == null ? "" : author;
        this.approver = approver == null ? "" : approver;
        this.approvedAt = approvedAt == null ? "" : approvedAt;
        this.dictionaryVersion = dictionaryVersion == null ? "v1" : dictionaryVersion;
    }

    public static Builder builder(String code, String version) { return new Builder(code, version); }

    public String getIndicatorCode() { return indicatorCode; }
    public String getDefinitionVersion() { return definitionVersion; }
    public String getTitle() { return title; }
    public ConditionTree getNumerator() { return numerator; }
    public ConditionTree getDenominator() { return denominator; }
    public List<ConditionTree> getExclusions() { return exclusions; }
    public List<String> getDeptCodes() { return deptCodes; }
    public List<String> getPeriods() { return periods; }
    public List<DataSourceRef> getDataSources() { return dataSources; }
    public String getEffectiveFrom() { return effectiveFrom; }
    public String getEffectiveTo() { return effectiveTo; }
    public MetricStatus getStatus() { return status; }
    public String getAuthor() { return author; }
    public String getApprover() { return approver; }
    public String getApprovedAt() { return approvedAt; }
    public String getDictionaryVersion() { return dictionaryVersion; }

    public String key() { return indicatorCode + "|" + definitionVersion; }

    /** 分母口径指纹：依赖边按此匹配，不按名称匹配。 */
    public String denominatorFingerprint() {
        return denominator == null ? Hashes.sha256("(无分母)") : denominator.fingerprint();
    }

    /** 口径内容指纹：canonical form 序列化后的 sha256，用作复算缓存键。 */
    public String contentFingerprint() { return Hashes.sha256(canonicalJson()); }

    /** 口径声明依赖的全部字段名。 */
    public Set<String> declaredFields() {
        Set<String> out = new LinkedHashSet<>();
        if (numerator != null) out.addAll(numerator.declaredFields());
        if (denominator != null) out.addAll(denominator.declaredFields());
        for (ConditionTree e : exclusions) out.addAll(e.declaredFields());
        for (DataSourceRef d : dataSources) out.add(d.getField());
        return out;
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("indicator_code", indicatorCode);
        o.put("definition_version", definitionVersion);
        o.put("title", title);
        if (numerator != null) o.set("numerator", numerator.toJson());
        if (denominator != null) o.set("denominator", denominator.toJson());
        ArrayNode ex = o.putArray("exclusions");
        for (ConditionTree e : exclusions) ex.add(e.toJson());
        ObjectNode scope = o.putObject("scope");
        ArrayNode depts = scope.putArray("deptCodes");
        for (String d : deptCodes) depts.add(d);
        ArrayNode ps = scope.putArray("periods");
        for (String p : periods) ps.add(p);
        ArrayNode ds = o.putArray("data_sources");
        for (DataSourceRef d : dataSources) ds.add(d.toJson());
        o.put("effective_from", effectiveFrom);
        o.put("effective_to", effectiveTo);
        o.put("status", status.name());
        o.put("author", author);
        o.put("approver", approver);
        o.put("approved_at", approvedAt);
        o.put("dictionary_version", dictionaryVersion);
        return o;
    }

    public static MetricDefinition fromJson(JsonNode n) {
        Builder b = builder(n.path("indicator_code").asText(""), n.path("definition_version").asText(""))
                .title(n.path("title").asText(""));
        if (n.has("numerator")) b.numerator(ConditionTree.fromJson(n.path("numerator")));
        if (n.has("denominator")) b.denominator(ConditionTree.fromJson(n.path("denominator")));
        JsonNode ex = n.path("exclusions");
        if (ex.isArray()) for (JsonNode e : ex) b.addExclusion(ConditionTree.fromJson(e));
        JsonNode scope = n.path("scope");
        b.deptCodes(JsonSupport.stringList(scope.path("deptCodes")));
        b.periods(JsonSupport.stringList(scope.path("periods")));
        JsonNode ds = n.path("data_sources");
        if (ds.isArray()) for (JsonNode d : ds) b.addDataSource(DataSourceRef.fromJson(d));
        return b.effectiveFrom(n.path("effective_from").asText(""))
                .effectiveTo(n.path("effective_to").asText(""))
                .status(MetricStatus.of(n.path("status").asText("DRAFT")))
                .author(n.path("author").asText(""))
                .approver(n.path("approver").asText(""))
                .approvedAt(n.path("approved_at").asText(""))
                .dictionaryVersion(n.path("dictionary_version").asText("v1"))
                .build();
    }

    public static MetricDefinition fromJson(String text) { return fromJson(JsonSupport.parse(text)); }

    /** canonical form：归一化后的条件树 + 排序后的集合字段，用于内容指纹与差异比较。 */
    public String canonicalJson() {
        ObjectNode o = JsonSupport.object();
        o.put("indicator_code", indicatorCode);
        o.put("definition_version", definitionVersion);
        o.put("title", title);
        if (numerator != null) o.set("numerator", Canonicalizer.canonicalNode(numerator));
        if (denominator != null) o.set("denominator", Canonicalizer.canonicalNode(denominator));
        ArrayNode ex = o.putArray("exclusions");
        List<String> exJsons = new ArrayList<>();
        for (ConditionTree e : exclusions) exJsons.add(Canonicalizer.canonicalNode(e).toString());
        Collections.sort(exJsons);
        for (String s : exJsons) ex.add(JsonSupport.parse(s));
        ArrayNode depts = o.putArray("deptCodes");
        new ArrayList<>(new LinkedHashSet<>(deptCodes)).stream().sorted().forEach(depts::add);
        ArrayNode ps = o.putArray("periods");
        new ArrayList<>(new LinkedHashSet<>(periods)).stream().sorted().forEach(ps::add);
        o.put("effective_from", effectiveFrom);
        o.put("effective_to", effectiveTo);
        return o.toString();
    }

    /** 内容是否完全一致（不含状态/审批字段）。 */
    public boolean sameContent(MetricDefinition other) {
        return other != null && canonicalJson().equals(other.canonicalJson());
    }

    public Builder toBuilder() {
        return builder(indicatorCode, definitionVersion).title(title)
                .numerator(numerator).denominator(denominator)
                .exclusions(exclusions).deptCodes(deptCodes).periods(periods)
                .dataSources(dataSources).effectiveFrom(effectiveFrom).effectiveTo(effectiveTo)
                .status(status).author(author).approver(approver).approvedAt(approvedAt)
                .dictionaryVersion(dictionaryVersion);
    }

    /** 数据源字段引用 {source, entity, field}。 */
    public static final class DataSourceRef {
        private final String source, entity, field;
        public DataSourceRef(String source, String entity, String field) {
            this.source = source == null ? "" : source;
            this.entity = entity == null ? "" : entity;
            this.field = field == null ? "" : field;
        }
        public String getSource() { return source; }
        public String getEntity() { return entity; }
        public String getField() { return field; }
        public ObjectNode toJson() {
            ObjectNode o = JsonSupport.object();
            o.put("source", source); o.put("entity", entity); o.put("field", field);
            return o;
        }
        public static DataSourceRef fromJson(JsonNode n) {
            return new DataSourceRef(n.path("source").asText(""),
                    n.path("entity").asText(""), n.path("field").asText(""));
        }
        @Override public boolean equals(Object o) {
            return o instanceof DataSourceRef d && source.equals(d.source)
                    && entity.equals(d.entity) && field.equals(d.field);
        }
        @Override public int hashCode() { return Objects.hash(source, entity, field); }
    }

    public static final class Builder {
        private final String code, version;
        private String title = "";
        private ConditionTree numerator, denominator;
        private List<ConditionTree> exclusions = new ArrayList<>();
        private List<String> deptCodes = new ArrayList<>();
        private List<String> periods = new ArrayList<>();
        private List<DataSourceRef> dataSources = new ArrayList<>();
        private String effectiveFrom = "", effectiveTo = "";
        private MetricStatus status = MetricStatus.DRAFT;
        private String author = "", approver = "", approvedAt = "", dictionaryVersion = "v1";

        private Builder(String code, String version) { this.code = code; this.version = version; }
        public Builder title(String v) { this.title = v; return this; }
        public Builder numerator(ConditionTree v) { this.numerator = v; return this; }
        public Builder denominator(ConditionTree v) { this.denominator = v; return this; }
        public Builder exclusions(List<ConditionTree> v) { this.exclusions = new ArrayList<>(v); return this; }
        public Builder addExclusion(ConditionTree v) { this.exclusions.add(v); return this; }
        public Builder deptCodes(List<String> v) { this.deptCodes = new ArrayList<>(v); return this; }
        public Builder periods(List<String> v) { this.periods = new ArrayList<>(v); return this; }
        public Builder dataSources(List<DataSourceRef> v) { this.dataSources = new ArrayList<>(v); return this; }
        public Builder addDataSource(DataSourceRef v) { this.dataSources.add(v); return this; }
        public Builder effectiveFrom(String v) { this.effectiveFrom = v; return this; }
        public Builder effectiveTo(String v) { this.effectiveTo = v; return this; }
        public Builder status(MetricStatus v) { this.status = v; return this; }
        public Builder author(String v) { this.author = v; return this; }
        public Builder approver(String v) { this.approver = v; return this; }
        public Builder approvedAt(String v) { this.approvedAt = v; return this; }
        public Builder dictionaryVersion(String v) { this.dictionaryVersion = v; return this; }
        public MetricDefinition build() {
            return new MetricDefinition(code, version, title, numerator, denominator,
                    exclusions, deptCodes, periods, dataSources, effectiveFrom, effectiveTo,
                    status, author, approver, approvedAt, dictionaryVersion);
        }
    }
}
