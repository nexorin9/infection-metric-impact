package com.infection.metric.graph;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 任务22：Kahn 拓扑排序 + 环链 DFS，自环/短环/长环分类与可读链。 */
class CycleDetectTest {

    @Test
    void 自环给出正确链且标注自环() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("A|v1").addEdge("A|v1", "A|v1");

        CycleResult r = CycleDetector.detect(g);
        assertTrue(r.hasCycle());
        assertEquals(CycleResult.CycleType.SELF, r.getType());
        assertEquals("自环", r.getType().getLabel());
        assertEquals(List.of("A|v1", "A|v1"), r.getChain());
        assertEquals("口径依赖成环：A|v1 → A|v1", r.getMessage());
        assertEquals(1, r.getInvolved().size());
        assertEquals("A", CycleResult.codeOf("A|v1"));
        assertEquals("v1", CycleResult.versionOf("A|v1"));
    }

    @Test
    void 短环给出正确链且标注短环() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("A|v1").addNode("B|v1").addNode("C|v1");
        g.addEdge("A|v1", "B|v1").addEdge("B|v1", "A|v1");

        CycleResult r = CycleDetector.detect(g);
        assertEquals(CycleResult.CycleType.SHORT, r.getType());
        assertEquals("短环", r.getType().getLabel());
        List<String> chain = r.getChain();
        assertEquals(3, chain.size());
        assertEquals(chain.get(0), chain.get(chain.size() - 1), "链首尾相接");
        Set<String> distinct = new LinkedHashSet<>(chain);
        assertEquals(2, distinct.size());
        assertEquals(Set.of("A|v1", "B|v1"), distinct);
        assertTrue(r.getMessage().contains("成环"));
        assertTrue(r.getMessage().contains("→"));
    }

    @Test
    void 长环给出正确链且标注长环() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("A|v1").addNode("B|v1").addNode("C|v1").addNode("D|v1").addNode("E|v1");
        g.addEdge("A|v1", "B|v1").addEdge("B|v1", "C|v1").addEdge("C|v1", "A|v1");

        CycleResult r = CycleDetector.detect(g);
        assertEquals(CycleResult.CycleType.LONG, r.getType());
        assertEquals("长环", r.getType().getLabel());
        List<String> chain = r.getChain();
        assertEquals(4, chain.size(), "A → B → C → A 共 4 个节点位");
        assertEquals(chain.get(0), chain.get(chain.size() - 1));
        assertEquals(Set.of("A|v1", "B|v1", "C|v1"), new LinkedHashSet<>(chain));
        assertTrue(r.chainText().contains("A|v1 → B|v1 → C|v1 → A|v1"));
    }

    @Test
    void 无环图拓扑序完整且节点不漏() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("A|v1").addNode("B|v1").addNode("C|v1").addNode("D|v1").addNode("E|v1");
        g.addEdge("A|v1", "B|v1").addEdge("A|v1", "C|v1")
                .addEdge("B|v1", "D|v1").addEdge("C|v1", "D|v1").addEdge("D|v1", "E|v1");

        CycleResult r = CycleDetector.detect(g);
        assertFalse(r.hasCycle());
        assertEquals(CycleResult.CycleType.NONE, r.getType());
        assertTrue(r.getChain().isEmpty());
        assertTrue(r.getCycleNodes().isEmpty());

        List<String> topo = r.getTopologicalOrder();
        assertEquals(5, topo.size(), "拓扑序节点不漏");
        assertEquals(Set.of("A|v1", "B|v1", "C|v1", "D|v1", "E|v1"), new HashSet<>(topo));
        // 每条边 source 必须排在 target 之前（合法拓扑序）
        String[][] edges = {{"A|v1", "B|v1"}, {"A|v1", "C|v1"},
                {"B|v1", "D|v1"}, {"C|v1", "D|v1"}, {"D|v1", "E|v1"}};
        for (String[] e : edges) {
            assertTrue(topo.indexOf(e[0]) < topo.indexOf(e[1]),
                    "边 " + e[0] + "→" + e[1] + " 的先后顺序");
        }
    }

    @Test
    void 结果对象携带涉及指标列表与版本及可读文案() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("M-A|v1").addNode("M-B|v2").addNode("M-C|v3");
        g.addEdge("M-A|v1", "M-B|v2").addEdge("M-B|v2", "M-C|v3")
                .addEdge("M-C|v3", "M-A|v1");

        CycleResult r = CycleDetector.detect(g);
        assertTrue(r.hasCycle());
        assertEquals(3, r.getInvolved().size());
        for (String id : r.getInvolved()) {
            assertFalse(CycleResult.codeOf(id).isBlank());
            assertFalse(CycleResult.versionOf(id).isBlank(), "涉及指标须带版本");
        }
        assertTrue(r.involvedDisplay().stream().allMatch(s -> s.contains("版本")));
        assertTrue(r.getMessage().startsWith("口径依赖成环："));

        JsonNode n = r.toJson();
        assertTrue(n.path("has_cycle").asBoolean());
        assertEquals("LONG", n.path("type").asText());
        assertTrue(n.path("chain").isArray());
        assertTrue(n.path("involved").isArray());
        assertTrue(n.path("message").asText().contains("成环"));
    }
}
