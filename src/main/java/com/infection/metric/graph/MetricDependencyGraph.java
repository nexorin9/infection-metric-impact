package com.infection.metric.graph;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 口径依赖有向图：节点 = 口径条目 id（约定 {@code indicator_code|definition_version}），
 * 边 = 口径依赖。
 *
 * <p><b>方向约定</b>：{@code addEdge(source, target)} 表示「source 依赖 target」。
 * 因此：
 * <ul>
 *   <li>「id 依赖谁」沿出边（source→target）走；</li>
 *   <li>「谁依赖 id」沿入边反向走 —— 这正是「改一条口径 → 哪些指标受影响」的方向。</li>
 * </ul>
 *
 * <p><b>build 期六类边界</b>（前四类拒绝 build 并分类报错，后两类查询时报错）：
 * <ol>
 *   <li>自环：{@code A → A}；</li>
 *   <li>短环：{@code A → B → A}（2 节点）；</li>
 *   <li>长环：{@code A → B → C → A}（≥3 节点）；</li>
 *   <li>悬空边：引用不存在的口径；</li>
 *   <li>未 build 即查询 → E_GRAPH_003（illegal-state 语义）；</li>
 *   <li>查询未知 ID → E_GRAPH_004（not-found 语义）。</li>
 * </ol>
 * 成环错误走 E_GRAPH_001（文案含成环链，并在上下文标注 cycleType 自环/短环/长环），
 * 悬空边走 E_GRAPH_002。
 *
 * <p><b>闭包与非递归默认</b>：
 * {@link #getAncestorIds(String)} / {@link #getDescendantIds(String)} /
 * {@link #getIndirectAncestorIds(String)} 返回传递闭包；
 * {@link #getDirectDependencies(String)} 默认只返回直接依赖（非递归）；
 * 跨层须显式请求 {@link #expandDescendants(String, int)}，结果用 {@link ExpandItem} 标注 depth。
 */
public final class MetricDependencyGraph {

    private final LinkedHashSet<String> nodes = new LinkedHashSet<>();
    private final List<DependencyEdge> edges = new ArrayList<>();
    private boolean built = false;

    // build 后构建的邻接表与边依据索引。
    private Map<String, List<String>> out = Map.of();   // id -> 直接依赖（出边 target）
    private Map<String, List<String>> in = Map.of();    // id -> 直接依赖方（入边 source）
    private Map<String, String> evidenceByPair = Map.of(); // "source\u0000target" -> evidence

    public MetricDependencyGraph addNode(String id) {
        if (id == null || id.isBlank()) {
            throw ErrorCodes.error(ErrorCodes.E_GRAPH_004, "id", String.valueOf(id));
        }
        nodes.add(id);
        built = false;
        return this;
    }

    /** 增加一条简单依赖边（带默认人话依据）。节点须先经 addNode 加入。 */
    public MetricDependencyGraph addEdge(String source, String target) {
        return addEdge(new DependencyEdge(source, target, "",
                "口径依赖：" + source + " → " + target));
    }

    /** 增加一条带完整留痕的依赖边。 */
    public MetricDependencyGraph addEdge(DependencyEdge edge) {
        if (edge == null) {
            throw ErrorCodes.error(ErrorCodes.E_EDGE_001);
        }
        edges.add(edge);
        built = false;
        return this;
    }

    public boolean isBuilt() { return built; }
    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return edges.size(); }
    public Set<String> nodes() { return Collections.unmodifiableSet(new LinkedHashSet<>(nodes)); }
    public List<DependencyEdge> edges() { return List.copyOf(edges); }

    /**
     * 非抛出的构建期校验：返回所有构建错误（中文可读）与成环检测结果。
     * 影响面分析器据此在「成环/悬空」时返回空影响面且保留可读文案。
     */
    public BuildReport validate() {
        List<AppException> errors = new ArrayList<>();

        // ---- 悬空边：任一端点未建节点 ----
        LinkedHashSet<String> dangling = new LinkedHashSet<>();
        for (DependencyEdge e : edges) {
            if (!nodes.contains(e.getSource())) dangling.add(e.getSource());
            if (!nodes.contains(e.getTarget())) dangling.add(e.getTarget());
        }
        for (String missing : dangling) {
            errors.add(ErrorCodes.error(ErrorCodes.E_GRAPH_002, "target", missing)
                    .with("悬空边数", String.valueOf(countDanglingEdges())));
        }

        // ---- 成环：Kahn + 环链 DFS ----
        CycleResult cyc = CycleDetector.detect(nodes, edges);
        if (cyc.hasCycle()) {
            AppException err = ErrorCodes.error(ErrorCodes.E_GRAPH_001,
                    "chain", cyc.chainText(),
                    "cycleType", cyc.getType().getLabel());
            for (String m : cyc.involvedDisplay()) err = err.with("涉及指标", m);
            errors.add(err);
        }
        return new BuildReport(errors, cyc);
    }

    private long countDanglingEdges() {
        return edges.stream().filter(e ->
                !nodes.contains(e.getSource()) || !nodes.contains(e.getTarget())).count();
    }

    /**
     * 校验并构建邻接/闭包索引；任一构建错误即抛 AppException（取第一条），且保持未 build 状态。
     */
    public MetricDependencyGraph build() {
        BuildReport report = validate();
        if (!report.ok()) {
            built = false;
            throw report.exceptions().get(0);
        }
        Map<String, LinkedHashSet<String>> o = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> i = new LinkedHashMap<>();
        Map<String, String> ev = new LinkedHashMap<>();
        for (String n : nodes) {
            o.put(n, new LinkedHashSet<>());
            i.put(n, new LinkedHashSet<>());
        }
        for (DependencyEdge e : edges) {
            o.get(e.getSource()).add(e.getTarget());
            i.get(e.getTarget()).add(e.getSource());
            ev.put(e.getSource() + "\u0000" + e.getTarget(), e.getEvidence());
        }
        Map<String, List<String>> outMap = new LinkedHashMap<>();
        Map<String, List<String>> inMap = new LinkedHashMap<>();
        for (String n : nodes) {
            outMap.put(n, List.copyOf(o.get(n)));
            inMap.put(n, List.copyOf(i.get(n)));
        }
        this.out = Collections.unmodifiableMap(outMap);
        this.in = Collections.unmodifiableMap(inMap);
        this.evidenceByPair = Collections.unmodifiableMap(ev);
        this.built = true;
        return this;
    }

    private void requireBuilt(String id) {
        if (!built) throw ErrorCodes.error(ErrorCodes.E_GRAPH_003);
        if (!nodes.contains(id)) throw ErrorCodes.error(ErrorCodes.E_GRAPH_004, "id", id);
    }

    /** 直接依赖：只返回 source→target 的出边目标，非递归（默认语义）。 */
    public List<String> getDirectDependencies(String id) {
        requireBuilt(id);
        return List.copyOf(out.getOrDefault(id, List.of()));
    }

    /** 祖先闭包：id 传递依赖到的全部口径（直接 + 间接）。 */
    public Set<String> getAncestorIds(String id) {
        requireBuilt(id);
        LinkedHashSet<String> acc = new LinkedHashSet<>();
        bfs(id, out, acc);
        return Collections.unmodifiableSet(acc);
    }

    /** 后代闭包：传递依赖于 id 的全部口径 —— 即「改 id 后受影响的指标」。 */
    public Set<String> getDescendantIds(String id) {
        requireBuilt(id);
        LinkedHashSet<String> acc = new LinkedHashSet<>();
        bfs(id, in, acc);
        return Collections.unmodifiableSet(acc);
    }

    /** 间接祖先：祖先闭包中去掉直接依赖，仅保留 depth≥2 的层。 */
    public Set<String> getIndirectAncestorIds(String id) {
        requireBuilt(id);
        LinkedHashSet<String> acc = new LinkedHashSet<>(getAncestorIds(id));
        acc.removeAll(getDirectDependencies(id));
        return Collections.unmodifiableSet(acc);
    }

    private static void bfs(String start, Map<String, List<String>> adjacency, Set<String> acc) {
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        q.addLast(start);
        Set<String> seen = new LinkedHashSet<>();
        seen.add(start);
        while (!q.isEmpty()) {
            String cur = q.removeFirst();
            for (String next : adjacency.getOrDefault(cur, List.of())) {
                if (seen.add(next)) {
                    acc.add(next);
                    q.addLast(next);
                }
            }
        }
    }

    /**
     * 跨层显式展开：沿入边（谁依赖当前节点）BFS 到指定深度，返回带 depth 与来源边依据的条目。
     * depth=1 即默认非递归结果；depth≥2 才出现跨层项。已知 id 或非法深度由 requireBuilt/E_GRAPH_004 处理。
     */
    public List<ExpandItem> expandDescendants(String id, int depth) {
        requireBuilt(id);
        if (depth < 1) return List.of();
        List<ExpandItem> result = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(id);
        List<String> frontier = List.of(id);
        for (int d = 1; d <= depth && !frontier.isEmpty(); d++) {
            List<String> next = new ArrayList<>();
            for (String cur : frontier) {
                for (String src : in.getOrDefault(cur, List.of())) {
                    if (!visited.add(src)) continue;
                    String ev = evidenceByPair.getOrDefault(src + "\u0000" + cur,
                            "口径依赖：" + src + " → " + cur);
                    result.add(new ExpandItem(src, d, ev));
                    next.add(src);
                }
            }
            frontier = next;
        }
        return List.copyOf(result);
    }

    /** 规范化结构（节点集合 + 边集合），供快照与回归基线使用。 */
    public ObjectNode structureJson() {
        ObjectNode o = JsonSupport.object();
        ArrayNode ns = o.putArray("nodes");
        List<String> sortedNodes = new ArrayList<>(nodes);
        Collections.sort(sortedNodes);
        for (String n : sortedNodes) ns.add(n);
        ArrayNode es = o.putArray("edges");
        List<String> sortedEdges = new ArrayList<>();
        for (DependencyEdge e : edges) sortedEdges.add(e.canonical());
        Collections.sort(sortedEdges);
        for (String e : sortedEdges) es.add(e);
        return o;
    }

    /** 构建期校验报告：错误清单（可读）+ 成环检测结果。 */
    public static final class BuildReport {
        private final List<AppException> exceptions;
        private final CycleResult cycle;

        private BuildReport(List<AppException> exceptions, CycleResult cycle) {
            this.exceptions = Collections.unmodifiableList(new ArrayList<>(exceptions));
            this.cycle = cycle;
        }

        public boolean ok() { return exceptions.isEmpty(); }
        public List<AppException> exceptions() { return exceptions; }
        public CycleResult cycle() { return cycle; }

        /** 全部错误的中文可读渲染串。 */
        public List<String> renderErrors() {
            List<String> out = new ArrayList<>();
            for (AppException e : exceptions) out.add(e.render());
            return out;
        }

        public String firstErrorText() {
            return exceptions.isEmpty() ? "" : exceptions.get(0).render();
        }
    }
}
