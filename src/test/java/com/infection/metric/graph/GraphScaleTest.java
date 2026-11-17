package com.infection.metric.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 任务26：39 节点规模图、规范化快照回归与性能验收。 */
class GraphScaleTest {

    @Test
    void 规模图节点数为39且含12条脱敏院感口径() {
        MetricDependencyGraph g = GraphSnapshot.standardGraph();
        assertEquals(39, g.nodeCount());
        assertTrue(g.isBuilt());
        long hai = g.nodes().stream().filter(n -> n.startsWith("HAI-")).count();
        long mer = g.nodes().stream().filter(n -> n.startsWith("MER-")).count();
        assertEquals(12, hai, "12 条脱敏院感口径");
        assertEquals(27, mer, "27 条官方基准口径");
        assertTrue(g.edgeCount() > 0, "按复用分母建立了依赖边");
    }

    @Test
    void 快照稳定_两次生成一致() {
        GraphSnapshot s1 = GraphSnapshot.capture(GraphSnapshot.standardGraph());
        GraphSnapshot s2 = GraphSnapshot.capture(GraphSnapshot.standardGraph());
        assertEquals(s1.fingerprint(), s2.fingerprint(), "两次生成规范化哈希一致");
        assertEquals(s1.normalizedJson(), s2.normalizedJson());
        assertTrue(s1.identicalTo(s2));
        assertEquals(39, s1.nodeCount());
        assertTrue(s1.edgeCount() > 0);
    }

    @Test
    void 人为加一条边后能定位到该边() {
        GraphSnapshot base = GraphSnapshot.capture(GraphSnapshot.standardGraph());
        MetricDependencyGraph g2 = GraphSnapshot.standardGraph();
        String from = "MER-TL-03|2026.1";
        String to = "EXTRA|v1";
        g2.addNode(to);
        g2.addEdge(new DependencyEdge(from, to, "FP", "人为新增测试边"));
        GraphSnapshot changed = GraphSnapshot.capture(g2);

        GraphSnapshot.SnapshotDiff diff = changed.compare(base);
        assertFalse(diff.identical());
        assertTrue(diff.getAddedEdges().contains(from + "→" + to), "能定位到新增的那条边");
        assertEquals(1, diff.getAddedEdges().size());
        assertTrue(diff.getAddedNodes().contains(to));
        assertTrue(diff.getMissingEdges().isEmpty());
        assertTrue(diff.render().contains("新增边"));
    }

    @Test
    void 规范化JSON与基线比对显式列出新增与缺失边() {
        MetricDependencyGraph gA = new MetricDependencyGraph();
        gA.addNode("A").addNode("B").addNode("C");
        gA.addEdge("A", "B").addEdge("B", "C");
        GraphSnapshot sA = GraphSnapshot.capture(gA);

        MetricDependencyGraph gB = new MetricDependencyGraph();
        gB.addNode("A").addNode("B").addNode("C");
        gB.addEdge("A", "B").addEdge("C", "A"); // 删除 B→C，新增 C→A
        GraphSnapshot sB = GraphSnapshot.capture(gB);

        GraphSnapshot.SnapshotDiff diff = sB.compare(sA);
        assertFalse(diff.identical());
        assertTrue(diff.getMissingEdges().contains("B→C"));
        assertTrue(diff.getAddedEdges().contains("C→A"));
        assertTrue(diff.getAddedNodes().isEmpty());
        assertTrue(diff.getMissingNodes().isEmpty());
        assertTrue(diff.render().contains("新增边"));
        assertTrue(diff.render().contains("缺失边"));
    }

    @Test
    void 快照落盘可回读为基线(@TempDir Path temp) {
        GraphSnapshot base = GraphSnapshot.capture(GraphSnapshot.standardGraph());
        Path p = temp.resolve("baseline.json");
        base.writeTo(p);
        GraphSnapshot loaded = GraphSnapshot.read(p);
        assertEquals(base.fingerprint(), loaded.fingerprint());
        assertTrue(loaded.compare(base).identical());
    }

    @Test
    void 全图build加depth2全量影响面展开性能达标() {
        long t0 = System.nanoTime();
        MetricDependencyGraph g = GraphSnapshot.standardGraph(); // 含全图 build
        ExpandBudget budget = new ExpandBudget();
        int totalItems = 0;
        for (String node : g.nodes()) {
            totalItems += budget.expand(g, node, 2).size(); // depth=2 全量展开
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(totalItems > 0, "确实遍历出跨层影响面，非空壳");
        assertTrue(elapsedMs <= 3000,
                "39 节点全图 build + depth=2 全量展开应在单次 JVM 内 ≤ 3 秒，实际 " + elapsedMs + "ms");
    }
}
