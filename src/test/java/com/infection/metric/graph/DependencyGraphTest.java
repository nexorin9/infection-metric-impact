package com.infection.metric.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.metriclib.BenchmarkSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 任务3：build 期六类边界 + 祖先/后代闭包 + 非递归默认 + 端到端首份影响清单。 */
class DependencyGraphTest {

    @Test
    void 未build即查询抛E_GRAPH_003() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("A");
        assertFalse(g.isBuilt());

        AppException ex = assertThrows(AppException.class, () -> g.getDescendantIds("A"));
        assertEquals(ErrorCodes.E_GRAPH_003, ex.getCode());
        assertTrue(ex.getMessage().contains("build"));

        assertAll("四类查询在未 build 时都拒绝",
                () -> assertEquals(ErrorCodes.E_GRAPH_003,
                        assertThrows(AppException.class, () -> g.getAncestorIds("A")).getCode()),
                () -> assertEquals(ErrorCodes.E_GRAPH_003,
                        assertThrows(AppException.class, () -> g.getIndirectAncestorIds("A")).getCode()),
                () -> assertEquals(ErrorCodes.E_GRAPH_003,
                        assertThrows(AppException.class, () -> g.getDirectDependencies("A")).getCode()),
                () -> assertEquals(ErrorCodes.E_GRAPH_003,
                        assertThrows(AppException.class, () -> g.expandDescendants("A", 2)).getCode()));
    }

    @Test
    void 查询未知ID抛E_GRAPH_004() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("A").addNode("B").addEdge("A", "B");
        assertDoesNotThrow(g::build);
        assertTrue(g.isBuilt());

        AppException ex = assertThrows(AppException.class, () -> g.getDescendantIds("UNKNOWN"));
        assertEquals(ErrorCodes.E_GRAPH_004, ex.getCode());
        assertEquals("UNKNOWN", ex.getContext().get("id"));

        assertAll("四类查询对未知 ID 都拒绝",
                () -> assertEquals(ErrorCodes.E_GRAPH_004,
                        assertThrows(AppException.class, () -> g.getAncestorIds("UNKNOWN")).getCode()),
                () -> assertEquals(ErrorCodes.E_GRAPH_004,
                        assertThrows(AppException.class, () -> g.getIndirectAncestorIds("UNKNOWN")).getCode()),
                () -> assertEquals(ErrorCodes.E_GRAPH_004,
                        assertThrows(AppException.class, () -> g.getDirectDependencies("UNKNOWN")).getCode()));
    }

    @Test
    void 自环拒绝build且标注自环() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("A").addNode("B").addEdge("A", "A");

        AppException ex = assertThrows(AppException.class, g::build);
        assertEquals(ErrorCodes.E_GRAPH_001, ex.getCode());
        assertEquals("自环", ex.getContext().get("cycleType"));
        assertTrue(ex.getMessage().contains("成环"));
        assertTrue(ex.getMessage().contains("A → A"));
        assertFalse(g.isBuilt());
    }

    @Test
    void 短环拒绝build且标注短环() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("A").addNode("B").addNode("C");
        g.addEdge("A", "B").addEdge("B", "A");

        AppException ex = assertThrows(AppException.class, g::build);
        assertEquals(ErrorCodes.E_GRAPH_001, ex.getCode());
        assertEquals("短环", ex.getContext().get("cycleType"));
        String chain = ex.getContext().get("chain");
        assertTrue(chain.contains("A") && chain.contains("B"));
        assertTrue(chain.contains("→"));
    }

    @Test
    void 长环拒绝build且列出成环指标链() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("A").addNode("B").addNode("C").addNode("D");
        g.addEdge("A", "B").addEdge("B", "C").addEdge("C", "A");

        AppException ex = assertThrows(AppException.class, g::build);
        assertEquals(ErrorCodes.E_GRAPH_001, ex.getCode());
        assertEquals("长环", ex.getContext().get("cycleType"));
        String chain = ex.getContext().get("chain"); // A → B → C → A
        assertTrue(chain.contains("A") && chain.contains("B") && chain.contains("C"));
        assertEquals(3, chain.split("→").length - 1, "长环链应有 3 段箭头");
    }

    @Test
    void 悬空边引用不存在口径时拒绝build() {
        MetricDependencyGraph missingTarget = new MetricDependencyGraph();
        missingTarget.addNode("A").addNode("B").addEdge("A", "MISSING");
        AppException ex1 = assertThrows(AppException.class, missingTarget::build);
        assertEquals(ErrorCodes.E_GRAPH_002, ex1.getCode());
        assertEquals("MISSING", ex1.getContext().get("target"));

        MetricDependencyGraph missingSource = new MetricDependencyGraph();
        missingSource.addNode("B").addEdge("MISSING", "B");
        AppException ex2 = assertThrows(AppException.class, missingSource::build);
        assertEquals(ErrorCodes.E_GRAPH_002, ex2.getCode());
        assertEquals("MISSING", ex2.getContext().get("target"));
    }

    @Test
    void 祖先与后代闭包查询正确() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("A").addNode("B").addNode("C");
        g.addEdge("A", "B").addEdge("B", "C"); // A 依赖 B 依赖 C
        g.build();

        assertEquals(List.of("B"), g.getDirectDependencies("A"));
        assertEquals(Set.of("B", "C"), g.getAncestorIds("A"));
        assertEquals(Set.of("C"), g.getIndirectAncestorIds("A"));
        assertTrue(g.getAncestorIds("C").isEmpty());

        assertEquals(Set.of("A", "B"), g.getDescendantIds("C"));
        assertEquals(Set.of("A"), g.getDescendantIds("B"));
        assertTrue(g.getDescendantIds("A").isEmpty());
    }

    @Test
    void 默认只返回直接依赖_跨层显式展开标注深度() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("A").addNode("B").addNode("C").addNode("D");
        g.addEdge("A", "B").addEdge("B", "C").addEdge("C", "D");
        g.build();

        // 默认非递归：getDirectDependencies 只给直接依赖 B，不含跨层 C/D。
        assertEquals(List.of("B"), g.getDirectDependencies("A"));
        assertFalse(g.getDirectDependencies("A").contains("C"));
        assertFalse(g.getDirectDependencies("A").contains("D"));
        assertEquals(Set.of("B", "C", "D"), g.getAncestorIds("A"), "闭包方法才给跨层");

        // 显式跨层展开（沿「谁依赖该节点」方向）：D 的后代按层返回并标注 depth。
        List<ExpandItem> d1 = g.expandDescendants("D", 1);
        assertEquals(1, d1.size());
        assertEquals("C", d1.get(0).getNodeId());
        assertEquals(1, d1.get(0).getDepth());

        List<ExpandItem> d3 = g.expandDescendants("D", 3);
        assertEquals(3, d3.size());
        assertEquals(Set.of(1, 2, 3), d3.stream().map(ExpandItem::getDepth).collect(java.util.stream.Collectors.toSet()));
        assertTrue(d3.stream().anyMatch(it -> it.getNodeId().equals("A") && it.getDepth() == 3));
        assertTrue(d3.stream().allMatch(it -> !it.getEvidence().isBlank()), "每条结果带来源边依据");
    }

    @Test
    void 端到端_27项基准集建图并产出首份影响清单(@TempDir Path tempDir) {
        BenchmarkSet bs = BenchmarkSet.load();
        assertEquals(27, bs.size());

        MetricDependencyGraph g = ImpactListWriter.buildBenchmarkGraph(bs);
        assertEquals(27, g.nodeCount(), "官方 27 项全部入图");
        assertTrue(g.isBuilt());

        Path file = ImpactListWriter.writeFirstImpactList(tempDir, bs, "MER-TL-03",
                List.of("2026Q1", "2026Q2"));
        assertTrue(Files.exists(file));
        assertEquals("impact", file.getParent().getFileName().toString(), "产物落在 out/impact/");

        JsonNode root = JsonSupport.read(file);
        assertEquals("口径变更影响清单", root.path("title").asText());
        assertEquals("MER-TL-03", root.path("change").path("indicator_code").asText());

        JsonNode affected = root.path("affected_indicators");
        assertTrue(affected.isArray());
        assertEquals(7, affected.size(), "8 项复用同一分母，减去变更项应剩 7 项受影响");
        for (JsonNode a : affected) {
            assertFalse(a.path("indicator_code").asText().isBlank());
            assertFalse(a.path("name").asText().isBlank());
            assertFalse(a.path("definition_version").asText().isBlank());
            assertNotEquals("MER-TL-03", a.path("indicator_code").asText());
        }

        Set<String> quarters = new HashSet<>();
        for (JsonNode q : root.path("recompute_quarters")) quarters.add(q.asText());
        assertEquals(Set.of("2026Q1", "2026Q2"), quarters);
        assertFalse(root.path("source").asText().isBlank());
        assertFalse(root.path("note").asText().isBlank());
    }
}
