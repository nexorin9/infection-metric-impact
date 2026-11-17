package com.infection.metric.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.metriclib.MetricDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 指纹依赖描述：一条「指标 → 其分母口径」的依赖，按<b>分母定义内容哈希（口径指纹）</b>匹配。
 *
 * <p>每条记录描述某个指标复用某分母口径这一事实，携带：
 * <ul>
 *   <li>{@code fingerprint}：分母定义内容的 sha256 —— <b>唯一匹配根据</b>；</li>
 *   <li>{@code denominatorLabel}：分母名称，仅用于人话展示，<b>不参与匹配</b>；</li>
 *   <li>{@code periods}：口径 scope.periods，用于求需重算历史季度；</li>
 *   <li>指标编码 / 版本 / 名称，用于影响面清单。</li>
 * </ul>
 *
 * <p>同 {@code fingerprint} 的指标被 {@link ImpactAnalyzer} 连到同一个「分母口径节点」，
 * 从而形成「N 项复用同一分母」的结构；名称相同但定义（指纹）不同的两条口径，
 * 指纹必然不同 → 分到不同组 → <b>不建错边</b>。这正避免把官方基准集里
 * 「同期住院手术患者病历总数」与「同期出院手术患者病历总数」这类仅差一词的分母混为一个口径。
 */
public final class FingerprintEdge {

    /** 基准集指标默认适用的季度（基准集本身不带 scope.periods）。 */
    public static final List<String> DEFAULT_PERIODS =
            List.of("2026Q1", "2026Q2", "2026Q3", "2026Q4");

    private final String id;                 // code|version
    private final String code;
    private final String version;
    private final String title;
    private final String denominatorLabel;  // 名称，仅展示，不参与匹配
    private final String fingerprint;       // 分母定义内容哈希，唯一匹配根据
    private final List<String> periods;

    public FingerprintEdge(String code, String version, String title,
                           String denominatorLabel, String fingerprint, List<String> periods) {
        this.code = code == null ? "" : code;
        this.version = version == null ? "" : version;
        this.id = this.code + "|" + this.version;
        this.title = title == null ? "" : title;
        this.denominatorLabel = denominatorLabel == null ? "" : denominatorLabel;
        this.fingerprint = fingerprint == null || fingerprint.isBlank()
                ? Hashes.sha256("(无分母定义)") : fingerprint;
        this.periods = periods == null ? List.of() : List.copyOf(periods);
    }

    /** 从口径条目构造：指纹取 {@link MetricDefinition#denominatorFingerprint()}（分母定义内容哈希）。 */
    public static FingerprintEdge fromMetric(MetricDefinition md, String denominatorLabel) {
        return new FingerprintEdge(md.getIndicatorCode(), md.getDefinitionVersion(), md.getTitle(),
                denominatorLabel, md.denominatorFingerprint(), md.getPeriods());
    }

    /**
     * 从官方基准集指标节点构造：指纹取 {@code denominator_text}（分母定义内容）的 sha256，
     * <b>不取</b> {@code denominator_key}（名称）。名称只作为展示标签。
     */
    public static FingerprintEdge fromBenchmark(JsonNode indicator, String version, List<String> periods) {
        String text = indicator.path("denominator_text").asText("");
        String label = indicator.path("denominator_key").asText("");
        if (label.isEmpty()) label = text;
        String fp = Hashes.sha256(text);
        return new FingerprintEdge(indicator.path("indicator_code").asText(""),
                version, indicator.path("title").asText(""),
                label, fp, periods == null ? DEFAULT_PERIODS : periods);
    }

    public static FingerprintEdge fromBenchmark(JsonNode indicator, String version) {
        return fromBenchmark(indicator, version, DEFAULT_PERIODS);
    }

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getVersion() { return version; }
    public String getTitle() { return title; }
    public String getDenominatorLabel() { return denominatorLabel; }
    public String getFingerprint() { return fingerprint; }
    public List<String> getPeriods() { return periods; }

    /** 指纹短码，用于节点 id 与展示。 */
    public String fingerprintShort() {
        return fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12);
    }

    /** 该指纹对应的「分母口径节点」id：同指纹 → 同节点 → 同组复用。 */
    public String denominatorNodeId() { return "DEN@" + fingerprintShort(); }

    /** 是否因分母内容指纹相同而属于同一复用组（不按名称）。 */
    public boolean matches(FingerprintEdge other) {
        return other != null && fingerprint.equals(other.fingerprint);
    }

    /** 生成「指标 → 分母口径节点」的依赖边，带人话依据与指纹。 */
    public DependencyEdge toDependencyEdge(String targetNodeId) {
        String evidence = "复用「" + denominatorLabel + "」分母（口径指纹 " + fingerprintShort() + "）";
        return new DependencyEdge(id, targetNodeId, fingerprint, evidence, "", "口径指纹匹配");
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("id", id);
        o.put("code", code);
        o.put("version", version);
        o.put("title", title);
        o.put("denominator_label", denominatorLabel);
        o.put("fingerprint", fingerprint);
        var ps = o.putArray("periods");
        for (String p : periods) ps.add(p);
        return o;
    }

    public static FingerprintEdge fromJson(JsonNode n) {
        List<String> ps = new ArrayList<>();
        JsonNode arr = n.path("periods");
        if (arr.isArray()) for (JsonNode p : arr) ps.add(p.asText());
        return new FingerprintEdge(n.path("code").asText(""), n.path("version").asText(""),
                n.path("title").asText(""), n.path("denominator_label").asText(""),
                n.path("fingerprint").asText(""), ps);
    }
}
