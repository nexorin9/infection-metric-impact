package com.infection.metric.graph;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 任务23：深度控制（默认非递归 / depth 非法 / 层数上限）与展开预算（超节点上限显式失败）。 */
class ExpandDepthTest {

    /** 反向链：N1 依赖 N0，N2 依赖 N1，N3 依赖 N2。从 N0 展开后代可逐层展开。 */
    private static MetricDependencyGraph chainGraph() {
        MetricDependencyGraph g = new MetricDependencyGraph();
        g.addNode("N0").addNode("N1").addNode("N2").addNode("N3");
        g.addEdge(new DependencyEdge("N1", "N0", "FP", "N1 复用 N0 分母"));
        g.addEdge(new DependencyEdge("N2", "N1", "FP", "N2 复用 N1 分母"));
        g.addEdge(new DependencyEdge("N3", "N2", "FP", "N3 复用 N2 分母"));
        return g.build();
    }

    @Test
    void 默认上限为500节点5层() {
        ExpandBudget b = new ExpandBudget();
        assertEquals(500, b.getMaxNodes());
        assertEquals(5, b.getMaxLayers());
    }

    @Test
    void 默认只返回直接依赖不含跨层项() {
        MetricDependencyGraph g = chainGraph();
        List<ExpandItem> items = new ExpandBudget().expand(g, "N0");
        assertEquals(1, items.size());
        assertEquals("N1", items.get(0).getNodeId());
        assertEquals(1, items.get(0).getDepth());
        assertEquals(Set.of(1), items.stream().map(ExpandItem::getDepth).collect(Collectors.toSet()),
                "默认结果不含 depth≥2 的跨层项");
    }

    @Test
    void depth2出现第二层且深度正确() {
        MetricDependencyGraph g = chainGraph();
        List<ExpandItem> items = new ExpandBudget().expand(g, "N0", 2);
        assertEquals(2, items.size());
        assertEquals(Set.of(1, 2), items.stream().map(ExpandItem::getDepth).collect(Collectors.toSet()));
        assertTrue(items.stream().anyMatch(it -> it.getNodeId().equals("N2") && it.getDepth() == 2));
        assertEquals(3, new ExpandBudget().expand(g, "N0", 3).size());
    }

    @Test
    void 结果条目带depth与来源边依据() {
        MetricDependencyGraph g = chainGraph();
        List<ExpandItem> items = new ExpandBudget().expand(g, "N0", 2);
        assertTrue(items.stream().allMatch(it -> it.getDepth() >= 1));
        assertTrue(items.stream().allMatch(it -> !it.getEvidence().isBlank()),
                "每条结果带来源边依据");
        assertEquals("N1 复用 N0 分母", items.get(0).getEvidence());
    }

    @Test
    void depth非法抛E_GRAPH_006并给合法范围() {
        MetricDependencyGraph g = chainGraph();
        ExpandBudget b = new ExpandBudget();

        AppException zero = assertThrows(AppException.class, () -> b.expand(g, "N0", 0));
        assertEquals(ErrorCodes.E_GRAPH_006, zero.getCode());
        assertEquals("0", zero.getContext().get("depth"));
        assertTrue(zero.getContext().get("合法范围").contains("1"));
        assertTrue(zero.getContext().get("合法范围").contains("5"));

        assertEquals(ErrorCodes.E_GRAPH_006,
                assertThrows(AppException.class, () -> b.expand(g, "N0", -3)).getCode());
    }

    @Test
    void 超过层数上限被拒E_GRAPH_006() {
        MetricDependencyGraph g = chainGraph();
        ExpandBudget b = new ExpandBudget();
        AppException ex = assertThrows(AppException.class, () -> b.expand(g, "N0", 6));
        assertEquals(ErrorCodes.E_GRAPH_006, ex.getCode());
        assertEquals("6", ex.getContext().get("depth"));
    }

    @Test
    void 超节点预算抛E_GRAPH_005并给实际规模不静默截断() {
        MetricDependencyGraph big = new MetricDependencyGraph();
        big.addNode("ROOT");
        for (int i = 0; i < 10; i++) {
            big.addNode("C" + i);
            big.addEdge("C" + i, "ROOT"); // Ci 依赖 ROOT → ROOT 有 10 个直接依赖方
        }
        big.build();

        ExpandBudget small = new ExpandBudget(3, 5); // 节点上限 3
        AppException ex = assertThrows(AppException.class, () -> small.expand(big, "ROOT", 1));
        assertEquals(ErrorCodes.E_GRAPH_005, ex.getCode());
        assertEquals("10", ex.getContext().get("actual"), "给出实际规模");
        assertEquals("3", ex.getContext().get("limit"));
        assertTrue(ex.render().contains("10"));

        // 默认预算（500）下同一图可正常展开，证明确实是预算生效而非数据问题
        assertEquals(10, new ExpandBudget().expand(big, "ROOT", 1).size());
    }

    @Test
    void 未build即展开抛E_GRAPH_003() {
        MetricDependencyGraph unbuilt = new MetricDependencyGraph();
        unbuilt.addNode("A");
        AppException ex = assertThrows(AppException.class,
                () -> new ExpandBudget().expand(unbuilt, "A", 1));
        assertEquals(ErrorCodes.E_GRAPH_003, ex.getCode());
    }
}
