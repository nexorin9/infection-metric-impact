package com.infection.metric.graph;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依赖边依据库：每条依赖都必须说得出为什么，且被依赖口径内容变更后需人工复核。
 *
 * <p>职责：
 * <ul>
 *   <li>缺 {@code evidence}（人话依据）的边不允许写入 → 抛 E_EDGE_001；</li>
 *   <li>{@link #edgesFrom(String)} / {@link #edgesTo(String)} 反查全部出边 / 入边及其依据文本；</li>
 *   <li>被依赖口径内容变更后指纹变化 → 对应边标记「待复核」{@code reviewRequired=true}；
 *       待复核的边不计入影响面，须人工 {@link #confirm(String)} 后才继续计入。</li>
 * </ul>
 */
public final class EdgeEvidenceStore {

    private final Map<String, DependencyEdge> byId = new LinkedHashMap<>();

    /** 写入库：缺依据一律拒绝（E_EDGE_001），避免无据依赖混入影响面。 */
    public EdgeEvidenceStore add(DependencyEdge edge) {
        if (edge == null || !edge.hasEvidence()) {
            throw ErrorCodes.error(ErrorCodes.E_EDGE_001);
        }
        byId.put(edge.getEdgeId(), edge);
        return this;
    }

    public int size() { return byId.size(); }

    public DependencyEdge byId(String edgeId) { return byId.get(edgeId); }

    public List<DependencyEdge> all() { return List.copyOf(byId.values()); }

    /** 全部出边（source = id），带依据文本。 */
    public List<DependencyEdge> edgesFrom(String id) {
        List<DependencyEdge> out = new ArrayList<>();
        for (DependencyEdge e : byId.values()) {
            if (e.getSource().equals(id)) out.add(e);
        }
        return out;
    }

    /** 全部入边（target = id），带依据文本。 */
    public List<DependencyEdge> edgesTo(String id) {
        List<DependencyEdge> out = new ArrayList<>();
        for (DependencyEdge e : byId.values()) {
            if (e.getTarget().equals(id)) out.add(e);
        }
        return out;
    }

    /** 已确认（非待复核）的出边 —— 真正计入影响面的边。 */
    public List<DependencyEdge> confirmedEdgesFrom(String id) {
        List<DependencyEdge> out = new ArrayList<>();
        for (DependencyEdge e : edgesFrom(id)) {
            if (!e.isReviewRequired()) out.add(e);
        }
        return out;
    }

    /** 已确认（非待复核）的入边。 */
    public List<DependencyEdge> confirmedEdgesTo(String id) {
        List<DependencyEdge> out = new ArrayList<>();
        for (DependencyEdge e : edgesTo(id)) {
            if (!e.isReviewRequired()) out.add(e);
        }
        return out;
    }

    /**
     * 被依赖口径内容变更：新指纹与边上记录的旧指纹不一致的边标记为「待复核」。
     *
     * @param target        被依赖口径节点 id
     * @param newFingerprint 变更后的口径指纹
     * @return 被标记为待复核的边 id 清单
     */
    public List<String> markReviewRequired(String target, String newFingerprint) {
        List<String> marked = new ArrayList<>();
        for (DependencyEdge e : byId.values()) {
            if (e.getTarget().equals(target) && !e.getFingerprint().equals(newFingerprint)) {
                e.markReviewRequired();
                marked.add(e.getEdgeId());
            }
        }
        return marked;
    }

    /** 人工确认某条边（按 edgeId）：清除待复核标记，重新计入影响面。 */
    public void confirm(String edgeId) {
        DependencyEdge e = byId.get(edgeId);
        if (e != null) e.confirm();
    }

    public boolean isReviewRequired(String edgeId) {
        DependencyEdge e = byId.get(edgeId);
        return e != null && e.isReviewRequired();
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        ArrayNode arr = o.putArray("edges");
        for (DependencyEdge e : byId.values()) arr.add(e.toJson());
        return o;
    }

    public static EdgeEvidenceStore fromJson(ObjectNode n) {
        EdgeEvidenceStore s = new EdgeEvidenceStore();
        com.fasterxml.jackson.databind.JsonNode arr = n.path("edges");
        if (arr.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode e : arr) {
                s.add(DependencyEdge.fromJson(e));
            }
        }
        return s;
    }
}
