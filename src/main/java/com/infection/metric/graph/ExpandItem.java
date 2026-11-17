package com.infection.metric.graph;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.util.Objects;

/**
 * 影响面展开的一条结果：带深度标注与来源边依据，供界面分组显示。
 *
 * <p>{@code depth} 从 1 起算：depth=1 为直接依赖（默认非递归展开），
 * depth=2 及以上为显式请求得到的跨层项。{@code evidence} 是到达该节点所经过的
 * 那条依赖边的人话依据（见 {@link DependencyEdge#getEvidence()}），
 * 让每条结果都说得出「为什么受影响」。
 */
public final class ExpandItem {

    private final String nodeId;
    private final int depth;
    private final String evidence;

    public ExpandItem(String nodeId, int depth, String evidence) {
        this.nodeId = nodeId == null ? "" : nodeId;
        this.depth = depth;
        this.evidence = evidence == null ? "" : evidence;
    }

    /** 受影响（或被依赖）的口径条目 id。 */
    public String getNodeId() { return nodeId; }

    /** 相对起点的层级，1 为直接层。 */
    public int getDepth() { return depth; }

    /** 来源边的人话依据。 */
    public String getEvidence() { return evidence; }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("node_id", nodeId);
        o.put("depth", depth);
        o.put("evidence", evidence);
        return o;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ExpandItem i && depth == i.depth
                && nodeId.equals(i.nodeId) && evidence.equals(i.evidence);
    }

    @Override
    public int hashCode() { return Objects.hash(nodeId, depth, evidence); }

    @Override
    public String toString() { return nodeId + "@" + depth + "（" + evidence + "）"; }
}
