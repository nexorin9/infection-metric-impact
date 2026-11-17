package com.infection.metric.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 依赖图成环检测与环链定位。
 *
 * <p>算法两步：
 * <ol>
 *   <li><b>Kahn 拓扑排序</b>：按边 {@code source → target} 统计入度，
 *       逐层将入度为 0 的节点出队；入度始终降不到 0 的剩余节点即环内（及被环阻塞）节点集合。</li>
 *   <li><b>环链 DFS</b>：在剩余节点上沿出边深度优先，遇到「指向当前路径中某节点」的回边，
 *       即从该节点到当前节点的路径 + 回边拼成一条有序链 {@code A → B → C → A}。</li>
 * </ol>
 *
 * <p>按链上不同节点数区分自环（1）、短环（2）、长环（≥3），
 * 结果由 {@link CycleResult} 携带链、涉及指标（含版本）与可读文案。
 */
public final class CycleDetector {

    private CycleDetector() {}

    /** 基于图当前节点与边检测（不要求图已 build，便于对成环图直接定位）。 */
    public static CycleResult detect(MetricDependencyGraph graph) {
        return detect(graph.nodes(), graph.edges());
    }

    /**
     * 检测成环并给出环链。悬空边（端点不在 nodes 内）在此被忽略，
     * 由 {@link MetricDependencyGraph#validate()} 单独报「悬空边」错误。
     */
    public static CycleResult detect(Collection<String> nodes, Collection<DependencyEdge> edges) {
        Set<String> nodeSet = new LinkedHashSet<>(nodes);

        // 仅保留两端都在节点集合内的边，构造前向邻接表并统计入度。
        Map<String, List<String>> adj = new LinkedHashMap<>();
        Map<String, Integer> indeg = new LinkedHashMap<>();
        for (String n : nodeSet) {
            adj.put(n, new ArrayList<>());
            indeg.put(n, 0);
        }
        Set<String> pairSeen = new HashSet<>();
        for (DependencyEdge e : edges) {
            String s = e.getSource();
            String t = e.getTarget();
            if (!nodeSet.contains(s) || !nodeSet.contains(t)) continue;
            String pair = s + "\u0000" + t;
            if (!pairSeen.add(pair)) continue; // 平行边去重，避免入度重复
            adj.get(s).add(t);
            indeg.put(t, indeg.get(t) + 1);
        }

        // ---- Kahn：逐层出队入度为 0 的节点 ----
        Deque<String> queue = new ArrayDeque<>();
        for (String n : nodeSet) {
            if (indeg.get(n) == 0) queue.addLast(n);
        }
        List<String> topo = new ArrayList<>();
        Set<String> dequeued = new HashSet<>();
        while (!queue.isEmpty()) {
            String u = queue.removeFirst();
            topo.add(u);
            dequeued.add(u);
            for (String v : adj.get(u)) {
                int d = indeg.get(v) - 1;
                indeg.put(v, d);
                if (d == 0) queue.addLast(v);
            }
        }

        Set<String> remaining = new LinkedHashSet<>();
        for (String n : nodeSet) {
            if (!dequeued.contains(n)) remaining.add(n);
        }
        if (remaining.isEmpty()) {
            return CycleResult.noCycle(topo);
        }

        // ---- 在剩余节点上 DFS 找一条具体回边，拼出有序链 ----
        List<String> chain = findCycleChain(remaining, adj);
        if (chain == null) {
            // 理论上不会发生（剩余节点必有环），保守返回一个退化的环提示。
            List<String> fallback = new ArrayList<>(remaining);
            fallback.add(fallback.get(0));
            chain = fallback;
        }
        return CycleResult.cycle(chain, topo, remaining);
    }

    /** 在给定节点集合内沿出边 DFS，返回首尾相接的成环链，找不到返回 null。 */
    private static List<String> findCycleChain(Set<String> scope, Map<String, List<String>> adj) {
        for (String start : scope) {
            List<String> path = new ArrayList<>();
            Set<String> inPath = new HashSet<>();
            List<String> found = dfs(start, scope, adj, path, inPath);
            if (found != null) return found;
        }
        return null;
    }

    private static List<String> dfs(String u, Set<String> scope, Map<String, List<String>> adj,
                                    List<String> path, Set<String> inPath) {
        path.add(u);
        inPath.add(u);
        for (String v : adj.getOrDefault(u, List.of())) {
            if (!scope.contains(v)) continue;
            if (inPath.contains(v)) {
                // 回边 u → v：v 在当前路径中，截取 v..u 并补上 v 形成闭环。
                int i = path.indexOf(v);
                List<String> chain = new ArrayList<>(path.subList(i, path.size()));
                chain.add(v);
                return chain;
            }
            List<String> r = dfs(v, scope, adj, path, inPath);
            if (r != null) return r;
        }
        path.remove(path.size() - 1);
        inPath.remove(u);
        return null;
    }
}
