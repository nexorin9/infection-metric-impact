package com.infection.metric.graph;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;

import java.util.List;

/**
 * 影响面展开的深度控制与展开预算。
 *
 * <p>沿用源产品 {@code expandResources} 的「默认只返回直接成员」非递归语义：
 * 默认 {@code depth=1} 只给直接依赖，结果里不会出现跨层项。真要跨层必须显式传
 * {@code depth=2..N}，每条结果用 {@link ExpandItem#getDepth()} 标注层级、用
 * {@link ExpandItem#getEvidence()} 标注来源边依据。
 *
 * <p>预算：单次展开节点上限默认 500、层数（深度）上限默认 5；
 * 深度非法（≤0 或 &gt; 层数上限）抛 E_GRAPH_006 并给出合法范围；
 * 展开结果超过节点上限抛 E_GRAPH_005 并给出实际规模，绝不静默截断。
 */
public final class ExpandBudget {

    public static final int DEFAULT_MAX_NODES = 500;
    public static final int DEFAULT_MAX_LAYERS = 5;

    private final int maxNodes;
    private final int maxLayers;

    public ExpandBudget() { this(DEFAULT_MAX_NODES, DEFAULT_MAX_LAYERS); }

    public ExpandBudget(int maxNodes, int maxLayers) {
        this.maxNodes = maxNodes;
        this.maxLayers = maxLayers;
    }

    public int getMaxNodes() { return maxNodes; }
    public int getMaxLayers() { return maxLayers; }

    /** 默认展开：只返回直接依赖（depth=1）。 */
    public List<ExpandItem> expand(MetricDependencyGraph graph, String id) {
        return expand(graph, id, 1);
    }

    /**
     * 按指定深度展开影响面（沿「谁依赖 id」方向）。结果带 depth 与来源边依据。
     *
     * @throws AppException E_GRAPH_006 深度非法 / E_GRAPH_005 超节点预算 /
     *                       E_GRAPH_003 图未 build / E_GRAPH_004 未知 id
     */
    public List<ExpandItem> expand(MetricDependencyGraph graph, String id, int depth) {
        if (depth < 1 || depth > maxLayers) {
            throw ErrorCodes.error(ErrorCodes.E_GRAPH_006, "depth", String.valueOf(depth))
                    .with("合法范围", "1 至 " + maxLayers);
        }
        List<ExpandItem> items = graph.expandDescendants(id, depth);
        if (items.size() > maxNodes) {
            throw ErrorCodes.error(ErrorCodes.E_GRAPH_005,
                    "actual", String.valueOf(items.size()),
                    "limit", String.valueOf(maxNodes));
        }
        return items;
    }
}
