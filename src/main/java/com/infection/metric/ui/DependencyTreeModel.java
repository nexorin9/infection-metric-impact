package com.infection.metric.ui;

import com.infection.metric.common.ErrorCodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依赖树纯逻辑模型：按层级组织依赖边，每条边带口径指纹 + 人话依据 + 深度标注。
 *
 * <p>与 M2 的依赖边纪律一致：缺人话依据的边不允许入树（抛 E_EDGE_001），避免无据依赖混进影响面。
 * 本类不创建任何 Swing 组件，也不硬依赖 graph 包，便于 headless 测试独立装配。</p>
 */
public final class DependencyTreeModel {

    private final List<DependencyEdgeView> edges = new ArrayList<>();

    /** 添加一条带依据的边；依据为空时显式拒绝。 */
    public DependencyTreeModel addEdge(DependencyEdgeView edge) {
        if (edge.getEvidence() == null || edge.getEvidence().isBlank()) {
            throw ErrorCodes.error(ErrorCodes.E_EDGE_001);
        }
        if (edge.getFingerprint() == null || edge.getFingerprint().isBlank()) {
            throw ErrorCodes.error(ErrorCodes.E_EDGE_001);
        }
        edges.add(edge);
        return this;
    }

    public DependencyTreeModel addEdge(String from, String to, String fingerprint,
                                       String evidence, int depth) {
        return addEdge(new DependencyEdgeView(from, to, fingerprint, evidence, depth));
    }

    public List<DependencyEdgeView> allEdges() {
        return List.copyOf(edges);
    }

    public boolean isEmpty() { return edges.isEmpty(); }

    /** 每条边都带指纹与人话依据。 */
    public boolean allEdgesHaveBasis() {
        return edges.stream().allMatch(e ->
                !e.getFingerprint().isBlank() && !e.getEvidence().isBlank());
    }

    /** 深度不超过 maxDepth 的可见边（按深度升序）。 */
    public List<DependencyEdgeView> edgesUpToDepth(int maxDepth) {
        return edges.stream()
                .filter(e -> e.getDepth() <= maxDepth)
                .sorted(Comparator.comparingInt(DependencyEdgeView::getDepth))
                .toList();
    }

    /** 按深度分组，供树形层级展示。 */
    public Map<Integer, List<DependencyEdgeView>> levels() {
        Map<Integer, List<DependencyEdgeView>> byDepth = new LinkedHashMap<>();
        List<DependencyEdgeView> sorted = edges.stream()
                .sorted(Comparator.comparingInt(DependencyEdgeView::getDepth))
                .toList();
        for (DependencyEdgeView e : sorted) {
            byDepth.computeIfAbsent(e.getDepth(), k -> new ArrayList<>()).add(e);
        }
        return byDepth;
    }

    /** 渲染成带缩进、指纹、人话依据与深度标注的文本行。 */
    public List<String> renderLines(int maxDepth) {
        List<String> out = new ArrayList<>();
        for (DependencyEdgeView e : edgesUpToDepth(maxDepth)) {
            String indent = "  ".repeat(Math.max(0, e.getDepth() - 1));
            out.add(indent + "└─ [第" + e.getDepth() + "层] "
                    + e.getFromCode() + " → " + e.getToCode()
                    + " ｜ 指纹 " + e.getFingerprint()
                    + " ｜ 依据：" + e.getEvidence());
        }
        return out;
    }
}
