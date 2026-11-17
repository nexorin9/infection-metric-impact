package com.infection.metric.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 依赖图规模验收与快照回归基线。
 *
 * <p><b>规模图</b>：{@link #standardGraph()} 用 {@code /samples/synthetic-indicators.json}
 * 的 39 条口径（27 官方基准 + 12 脱敏院感）建图，边按复用分母（denominator_key）建立。
 *
 * <p><b>快照</b>：导出节点集合与边集合的规范化 JSON（各自排序后取 sha256）作为回归基线；
 * 两次生成稳定一致。{@link #compare(GraphSnapshot)} 把改动后的差异<b>显式列出</b>新增/缺失边
 * （及节点），不允许静默变化。
 */
public final class GraphSnapshot {

    private static final String RESOURCE = "/samples/synthetic-indicators.json";

    private final TreeSet<String> nodes;   // 已排序
    private final TreeSet<String> edges;   // "source→target"，已排序

    private GraphSnapshot(TreeSet<String> nodes, TreeSet<String> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    /** 从图当前结构捕获快照（不要求已 build，便于回归比对）。 */
    public static GraphSnapshot capture(MetricDependencyGraph graph) {
        TreeSet<String> ns = new TreeSet<>(graph.nodes());
        TreeSet<String> es = new TreeSet<>();
        for (DependencyEdge e : graph.edges()) es.add(e.canonical());
        return new GraphSnapshot(ns, es);
    }

    /**
     * 构建 39 节点规模图：27 官方 + 12 脱敏院感，按复用分母分组串成链状依赖边，并 build。
     */
    public static MetricDependencyGraph standardGraph() {
        JsonNode root = JsonSupport.readResource(RESOURCE);
        JsonNode arr = root.path("indicators");
        MetricDependencyGraph g = new MetricDependencyGraph();
        Map<String, List<String>> groups = new LinkedHashMap<>();

        if (arr.isArray()) {
            for (JsonNode n : arr) {
                String code = n.path("indicator_code").asText("");
                String version = n.path("definition_version").asText("2026.1");
                String id = code + "|" + version;
                g.addNode(id);
                String dk = n.path("denominator_key").asText("");
                if (!dk.isEmpty()) groups.computeIfAbsent(dk, k -> new ArrayList<>()).add(id);
            }
        }
        for (List<String> group : groups.values()) {
            group.sort(String::compareTo);
            for (int i = 1; i < group.size(); i++) {
                g.addEdge(group.get(i), group.get(i - 1)); // 后一个依赖前一个的分母
            }
        }
        return g.build();
    }

    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return edges.size(); }
    public TreeSet<String> getNodes() { return new TreeSet<>(nodes); }
    public TreeSet<String> getEdges() { return new TreeSet<>(edges); }

    /** 规范化 JSON（节点、边均排序），稳定可复现。 */
    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        ArrayNode ns = o.putArray("nodes");
        for (String n : nodes) ns.add(n);
        ArrayNode es = o.putArray("edges");
        for (String e : edges) es.add(e);
        return o;
    }

    public String normalizedJson() { return JsonSupport.compact(toJson()); }

    /** 基线哈希：规范化 JSON 的 sha256。 */
    public String fingerprint() { return Hashes.sha256(normalizedJson()); }

    public void writeTo(Path path) { JsonSupport.writeAtomic(path, toJson()); }

    public static GraphSnapshot read(Path path) { return fromJson(JsonSupport.read(path)); }

    public static GraphSnapshot fromJson(JsonNode n) {
        TreeSet<String> ns = new TreeSet<>();
        TreeSet<String> es = new TreeSet<>();
        JsonNode arrN = n.path("nodes");
        if (arrN.isArray()) for (JsonNode x : arrN) ns.add(x.asText());
        JsonNode arrE = n.path("edges");
        if (arrE.isArray()) for (JsonNode x : arrE) es.add(x.asText());
        return new GraphSnapshot(ns, es);
    }

    /** 与基线比对，显式列出新增/缺失的节点与边。 */
    public SnapshotDiff compare(GraphSnapshot baseline) {
        TreeSet<String> addedNodes = new TreeSet<>(nodes);
        addedNodes.removeAll(baseline.nodes);
        TreeSet<String> missingNodes = new TreeSet<>(baseline.nodes);
        missingNodes.removeAll(nodes);
        TreeSet<String> addedEdges = new TreeSet<>(edges);
        addedEdges.removeAll(baseline.edges);
        TreeSet<String> missingEdges = new TreeSet<>(baseline.edges);
        missingEdges.removeAll(edges);
        return new SnapshotDiff(
                new ArrayList<>(addedNodes), new ArrayList<>(missingNodes),
                new ArrayList<>(addedEdges), new ArrayList<>(missingEdges));
    }

    /** 两次快照是否完全一致（节点与边都无差异）。 */
    public boolean identicalTo(GraphSnapshot other) {
        return nodes.equals(other.nodes) && edges.equals(other.edges);
    }

    /** 回归比对差异：新增/缺失节点与新增/缺失边，全部显式列出。 */
    public static final class SnapshotDiff {
        private final List<String> addedNodes;
        private final List<String> missingNodes;
        private final List<String> addedEdges;
        private final List<String> missingEdges;

        public SnapshotDiff(List<String> addedNodes, List<String> missingNodes,
                            List<String> addedEdges, List<String> missingEdges) {
            this.addedNodes = Collections.unmodifiableList(new ArrayList<>(addedNodes));
            this.missingNodes = Collections.unmodifiableList(new ArrayList<>(missingNodes));
            this.addedEdges = Collections.unmodifiableList(new ArrayList<>(addedEdges));
            this.missingEdges = Collections.unmodifiableList(new ArrayList<>(missingEdges));
        }

        public List<String> getAddedNodes() { return addedNodes; }
        public List<String> getMissingNodes() { return missingNodes; }
        public List<String> getAddedEdges() { return addedEdges; }
        public List<String> getMissingEdges() { return missingEdges; }

        public boolean identical() {
            return addedNodes.isEmpty() && missingNodes.isEmpty()
                    && addedEdges.isEmpty() && missingEdges.isEmpty();
        }

        /** 中文可读差异描述。 */
        public String render() {
            if (identical()) return "与基线一致，无差异";
            StringBuilder sb = new StringBuilder();
            if (!addedEdges.isEmpty()) sb.append("新增边：").append(String.join("，", addedEdges)).append("；");
            if (!missingEdges.isEmpty()) sb.append("缺失边：").append(String.join("，", missingEdges)).append("；");
            if (!addedNodes.isEmpty()) sb.append("新增节点：").append(String.join("，", addedNodes)).append("；");
            if (!missingNodes.isEmpty()) sb.append("缺失节点：").append(String.join("，", missingNodes)).append("；");
            return sb.toString();
        }
    }
}
