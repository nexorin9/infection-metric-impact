package com.infection.metric.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 《口径变更影响清单》数据类：可落盘、可导出 JSON/CSV 的结构化产物。
 *
 * <p>包含：变更条目 + 受影响指标（编码/名称/版本）+ 需重算历史季度 +
 * 每条变更的人话说明 + 来源引用。空影响面时结构完整，{@code note} 明确说明「无需重算」，
 * 需重算季度为空时统一写「无历史季度需重算」，绝不留空数组让人误解。
 */
public final class ImpactList {

    /** 变更条目：被改的那一条口径。 */
    public static final class ChangeEntry {
        private final String code;
        private final String version;
        private final String title;
        private final String description;

        public ChangeEntry(String code, String version, String title, String description) {
            this.code = code == null ? "" : code;
            this.version = version == null ? "" : version;
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
        }

        public String getCode() { return code; }
        public String getVersion() { return version; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }

        public ObjectNode toJson() {
            ObjectNode o = JsonSupport.object();
            o.put("indicator_code", code);
            o.put("definition_version", version);
            o.put("title", title);
            o.put("description", description);
            return o;
        }

        static ChangeEntry fromJson(JsonNode n) {
            return new ChangeEntry(n.path("indicator_code").asText(""),
                    n.path("definition_version").asText(""),
                    n.path("title").asText(""), n.path("description").asText(""));
        }
    }

    /** 一行受影响指标。 */
    public static final class AffectedIndicator {
        private final String code;
        private final String name;
        private final String version;
        private final int depth;
        private final String explanation;
        private final String source;

        public AffectedIndicator(String code, String name, String version, int depth,
                                 String explanation, String source) {
            this.code = code == null ? "" : code;
            this.name = name == null ? "" : name;
            this.version = version == null ? "" : version;
            this.depth = depth;
            this.explanation = explanation == null ? "" : explanation;
            this.source = source == null ? "" : source;
        }

        public String getCode() { return code; }
        public String getName() { return name; }
        public String getVersion() { return version; }
        public int getDepth() { return depth; }
        public String getExplanation() { return explanation; }
        public String getSource() { return source; }

        public ObjectNode toJson() {
            ObjectNode o = JsonSupport.object();
            o.put("indicator_code", code);
            o.put("name", name);
            o.put("definition_version", version);
            o.put("depth", depth);
            o.put("explanation", explanation);
            o.put("source", source);
            return o;
        }

        static AffectedIndicator fromJson(JsonNode n) {
            return new AffectedIndicator(n.path("indicator_code").asText(""),
                    n.path("name").asText(""), n.path("definition_version").asText(""),
                    n.path("depth").asInt(1), n.path("explanation").asText(""),
                    n.path("source").asText(""));
        }
    }

    private final ChangeEntry change;
    private final List<String> recomputeQuarters;
    private final List<AffectedIndicator> affected;
    private final String source;
    private final String note;

    public ImpactList(ChangeEntry change, List<String> recomputeQuarters,
                      List<AffectedIndicator> affected, String source, String note) {
        this.change = change;
        this.recomputeQuarters = recomputeQuarters == null ? List.of() : List.copyOf(recomputeQuarters);
        this.affected = affected == null ? List.of() : List.copyOf(affected);
        this.source = source == null ? "" : source;
        this.note = note == null ? "" : note;
    }

    public ChangeEntry getChange() { return change; }
    public List<String> getRecomputeQuarters() { return recomputeQuarters; }
    public List<AffectedIndicator> getAffected() { return affected; }
    public String getSource() { return source; }
    public String getNote() { return note; }

    /** 人话季度文本：无交集时明确写「无历史季度需重算」。 */
    public String recomputeQuartersText() {
        return recomputeQuarters.isEmpty() ? "无历史季度需重算"
                : String.join("，", recomputeQuarters);
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("title", "口径变更影响清单");
        o.set("change", change.toJson());
        ArrayNode qs = o.putArray("recompute_quarters");
        for (String q : recomputeQuarters) qs.add(q);
        o.put("recompute_quarters_text", recomputeQuartersText());
        ArrayNode arr = o.putArray("affected_indicators");
        for (AffectedIndicator a : affected) arr.add(a.toJson());
        o.put("affected_count", affected.size());
        o.put("source", source);
        o.put("note", note);
        return o;
    }

    public static ImpactList fromJson(JsonNode n) {
        List<String> qs = new ArrayList<>();
        JsonNode qn = n.path("recompute_quarters");
        if (qn.isArray()) for (JsonNode q : qn) qs.add(q.asText());
        List<AffectedIndicator> as = new ArrayList<>();
        JsonNode an = n.path("affected_indicators");
        if (an.isArray()) for (JsonNode a : an) as.add(AffectedIndicator.fromJson(a));
        return new ImpactList(ChangeEntry.fromJson(n.path("change")), qs, as,
                n.path("source").asText(""), n.path("note").asText(""));
    }

    /** 原子落盘（pretty JSON）。 */
    public void writeTo(Path path) {
        JsonSupport.writeAtomic(path, toJson());
    }
}
