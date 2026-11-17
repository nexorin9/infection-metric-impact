package com.infection.metric.graph;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 成环检测结果：携带成环链、涉及指标列表（含版本）、拓扑序与可读文案。
 *
 * <p>成环链 {@code chain} 形如 {@code [A, B, C, A]}（首尾相接），
 * 文案形如「口径依赖成环：A → B → C → A」。按链上不同节点数区分三类环：
 * 自环（1 节点）、短环（2 节点）、长环（≥3 节点）。节点 id 本身为
 * {@code indicator_code|definition_version}，故天然携带版本信息。
 */
public final class CycleResult {

    /** 环类型：无环 / 自环 / 短环 / 长环。 */
    public enum CycleType {
        NONE("无环"), SELF("自环"), SHORT("短环"), LONG("长环");
        private final String label;
        CycleType(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    private final boolean hasCycle;
    private final CycleType type;
    private final List<String> chain;
    private final List<String> involved;
    private final List<String> topologicalOrder;
    private final Set<String> cycleNodes;
    private final String message;

    private CycleResult(boolean hasCycle, CycleType type, List<String> chain,
                        List<String> involved, List<String> topologicalOrder,
                        Set<String> cycleNodes) {
        this.hasCycle = hasCycle;
        this.type = type;
        this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
        this.involved = Collections.unmodifiableList(new ArrayList<>(involved));
        this.topologicalOrder = Collections.unmodifiableList(new ArrayList<>(topologicalOrder));
        this.cycleNodes = Collections.unmodifiableSet(new LinkedHashSet<>(cycleNodes));
        this.message = hasCycle
                ? "口径依赖成环：" + String.join(" → ", chain)
                : "无环";
    }

    /** 构造「无环」结果，携带完整拓扑序。 */
    public static CycleResult noCycle(List<String> topologicalOrder) {
        return new CycleResult(false, CycleType.NONE, List.of(), List.of(),
                topologicalOrder, Set.of());
    }

    /** 构造「成环」结果，自动按链上不同节点数分类。 */
    public static CycleResult cycle(List<String> chain, List<String> topologicalOrder,
                                    Set<String> cycleNodes) {
        // 链首尾相接，去掉重复的尾节点即为涉及的不同节点。
        List<String> distinct = chain.isEmpty()
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(chain));
        int distinctCount = distinct.size();
        CycleType type = distinctCount <= 1 ? CycleType.SELF
                : distinctCount == 2 ? CycleType.SHORT : CycleType.LONG;
        return new CycleResult(true, type, chain, distinct, topologicalOrder, cycleNodes);
    }

    public boolean hasCycle() { return hasCycle; }
    public boolean isHasCycle() { return hasCycle; }
    public CycleType getType() { return type; }

    /** 有序成环链，首尾为同一节点，例如 [A,B,C,A]。 */
    public List<String> getChain() { return chain; }

    /** 链文本，例如「A → B → C → A」。 */
    public String chainText() { return String.join(" → ", chain); }

    /** 涉及的指标 id 列表（去重、含版本）。 */
    public List<String> getInvolved() { return involved; }

    /** 可读文案「口径依赖成环：A → B → C → A」。 */
    public String getMessage() { return message; }

    /** 无环时为完整拓扑序；有环时为已成功出队的节点序列。 */
    public List<String> getTopologicalOrder() { return topologicalOrder; }

    /** Kahn 之后剩余的节点（入度始终大于 0），即环内及被环阻塞的节点集合。 */
    public Set<String> getCycleNodes() { return cycleNodes; }

    /** 从 id（code|version）拆出指标编码。 */
    public static String codeOf(String id) {
        int i = id.indexOf('|');
        return i < 0 ? id : id.substring(0, i);
    }

    /** 从 id（code|version）拆出定义版本。 */
    public static String versionOf(String id) {
        int i = id.indexOf('|');
        return i < 0 ? "" : id.substring(i + 1);
    }

    /** 涉及指标的「编码（版本）」展示清单，供错误文案列出指标与版本。 */
    public List<String> involvedDisplay() {
        List<String> out = new ArrayList<>();
        for (String id : involved) {
            out.add(codeOf(id) + "（版本 " + versionOf(id) + "）");
        }
        return out;
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("has_cycle", hasCycle);
        o.put("type", type.name());
        o.put("type_label", type.getLabel());
        ArrayNode ch = o.putArray("chain");
        for (String s : chain) ch.add(s);
        ArrayNode iv = o.putArray("involved");
        for (String s : involved) iv.add(s);
        ArrayNode tp = o.putArray("topological_order");
        for (String s : topologicalOrder) tp.add(s);
        o.put("message", message);
        return o;
    }
}
