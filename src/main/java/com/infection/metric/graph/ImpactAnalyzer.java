package com.infection.metric.graph;

import com.infection.metric.common.ErrorCodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 口径影响面分析器：按<b>分母定义内容哈希（口径指纹）</b>匹配建依赖边，产出受影响指标与需重算历史季度。
 *
 * <p><b>建图</b>：按 {@link FingerprintEdge#getFingerprint()} 把指标分组。同一指纹的 N 项指标
 * （N≥2）连到同一个「分母口径节点 {@code DEN@<指纹>}」，边为「指标 → 分母口径节点」，
 * 依据为人话「复用「XX」分母（口径指纹 …）」。名称相同但内容（指纹）不同的两条口径
 * 指纹不同 → 不同分组 → 不建错边；仅差一词的两个官方手术分母同理天然分开。
 *
 * <p><b>影响面</b>：改一条口径时，展开其所在分母组的全部其它复用指标（默认 depth=1 直接层，
 * 跨层须显式请求 depth≥2 并用 {@link ImpactRow#getDepth()} 标注）。需重算历史季度 =
 * 变更口径 {@code scope.periods} 与「已落库结果分区」{@link #withStoredPeriods(Collection)} 求交；
 * 无交集明确写「无历史季度需重算」。
 *
 * <p><b>失败不中断</b>：成环 / 悬空边时依赖图 build 失败，分析器返回「受影响为空 + 可读错误文案」
 * 的结果，不抛异常、不静默改数据。
 */
public final class ImpactAnalyzer {

    private final List<FingerprintEdge> entries = new ArrayList<>();
    private final List<String> extraNodes = new ArrayList<>();
    private final List<DependencyEdge> extraEdges = new ArrayList<>();
    private Set<String> storedPeriods = new LinkedHashSet<>();
    private int maxNodes = ExpandBudget.DEFAULT_MAX_NODES;
    private int maxLayers = ExpandBudget.DEFAULT_MAX_LAYERS;

    private boolean built = false;
    private boolean invalid = false;
    private List<String> buildErrors = new ArrayList<>();
    private MetricDependencyGraph graph;
    private final Map<String, FingerprintEdge> entryById = new LinkedHashMap<>();
    private final Map<String, List<FingerprintEdge>> groups = new LinkedHashMap<>();
    private final Map<String, String> denNodeByFingerprint = new LinkedHashMap<>();

    public ImpactAnalyzer addEntry(FingerprintEdge e) { entries.add(e); return this; }
    public ImpactAnalyzer addEntries(Collection<FingerprintEdge> es) { entries.addAll(es); return this; }

    /** 额外节点（供成环/悬空等注入测试，或表示非复用口径节点）。 */
    public ImpactAnalyzer addNode(String id) { extraNodes.add(id); return this; }

    /** 额外依赖边（原样入图，供跨层或成环/悬空注入）。 */
    public ImpactAnalyzer addEdge(DependencyEdge e) { extraEdges.add(e); return this; }

    /** 注入「已落库结果分区」季度集合，scope.periods 与之求交得到需重算季度。 */
    public ImpactAnalyzer withStoredPeriods(Collection<String> periods) {
        this.storedPeriods = periods == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(periods);
        return this;
    }

    public ImpactAnalyzer withBudget(int maxNodes, int maxLayers) {
        this.maxNodes = maxNodes;
        this.maxLayers = maxLayers;
        return this;
    }

    /** 构建依赖图并校验；失败仅记录可读错误，不抛出。 */
    public ImpactAnalyzer build() {
        this.graph = new MetricDependencyGraph();
        entryById.clear();
        groups.clear();
        denNodeByFingerprint.clear();

        for (FingerprintEdge e : entries) {
            graph.addNode(e.getId());
            entryById.put(e.getId(), e);
            groups.computeIfAbsent(e.getFingerprint(), k -> new ArrayList<>()).add(e);
        }

        // 同指纹（≥2 项）→ 共享分母口径节点；仅 1 项的口径不复用，不建边。
        for (Map.Entry<String, List<FingerprintEdge>> g : groups.entrySet()) {
            if (g.getValue().size() < 2) continue;
            String denId = g.getValue().get(0).denominatorNodeId();
            graph.addNode(denId);
            denNodeByFingerprint.put(g.getKey(), denId);
            for (FingerprintEdge e : g.getValue()) {
                graph.addEdge(e.toDependencyEdge(denId));
            }
        }

        for (String n : extraNodes) graph.addNode(n);
        for (DependencyEdge e : extraEdges) graph.addEdge(e);

        MetricDependencyGraph.BuildReport report = graph.validate();
        if (!report.ok()) {
            this.invalid = true;
            this.built = false;
            this.buildErrors = report.renderErrors();
        } else {
            graph.build();
            this.invalid = false;
            this.built = true;
            this.buildErrors = new ArrayList<>();
        }
        return this;
    }

    /** 默认非递归分析（depth=1）。 */
    public ImpactAnalysisResult analyze(String changedId) { return analyze(changedId, 1); }

    /**
     * 影响面分析。depth=1 只给直接层（默认不出现跨层项）；depth≥2 显式跨层并标注。
     */
    public ImpactAnalysisResult analyze(String changedId, int depth) {
        if (invalid) {
            return ImpactAnalysisResult.emptyWithErrors(changedId, buildErrors);
        }
        if (!built) {
            return ImpactAnalysisResult.emptyWithErrors(changedId,
                    List.of(ErrorCodes.error(ErrorCodes.E_GRAPH_003).render()));
        }

        FingerprintEdge changed = entryById.get(changedId);
        String anchor;
        List<String> periods;
        if (changed != null) {
            String denId = denNodeByFingerprint.get(changed.getFingerprint());
            anchor = denId != null ? denId : changedId;
            periods = changed.getPeriods();
        } else if (graph.nodes().contains(changedId)) {
            // 变更的是分母口径节点或非复用口径节点：直接以该节点为起点展开。
            anchor = changedId;
            periods = List.of();
        } else {
            return ImpactAnalysisResult.unknown(changedId,
                    ErrorCodes.error(ErrorCodes.E_GRAPH_004, "id", changedId).render());
        }

        List<ExpandItem> items;
        try {
            items = new ExpandBudget(maxNodes, maxLayers).expand(graph, anchor, depth);
        } catch (RuntimeException ex) {
            // 深度/预算非法：作为可读错误返回，影响面为空。
            String text = ex instanceof com.infection.metric.common.AppException ae
                    ? ae.render() : ex.toString();
            return ImpactAnalysisResult.emptyWithErrors(changedId, List.of(text));
        }

        List<ImpactAnalysisResult.ImpactRow> rows = new ArrayList<>();
        for (ExpandItem it : items) {
            if (it.getNodeId().equals(changedId)) continue;
            FingerprintEdge e = entryById.get(it.getNodeId());
            String code = e != null ? e.getCode() : CycleResult.codeOf(it.getNodeId());
            String name = e != null ? e.getTitle() : it.getNodeId();
            String version = e != null ? e.getVersion() : CycleResult.versionOf(it.getNodeId());
            String explanation = "复用同一分母口径，需随变更一并复核并重算相关历史季度";
            String source = changed != null
                    ? changed.getDenominatorLabel() + "（口径指纹 " + changed.fingerprintShort() + "）"
                    : it.getEvidence();
            rows.add(new ImpactAnalysisResult.ImpactRow(it.getNodeId(), code, name, version,
                    it.getDepth(), it.getEvidence(), explanation, source));
        }

        List<String> recompute = intersect(periods, storedPeriods);
        String note = rows.isEmpty()
                ? "该口径分母无其它复用指标，无下游依赖，无需重算"
                : "共 " + rows.size() + " 项指标复用同一分母，需一并复核";
        return new ImpactAnalysisResult(changedId, rows, recompute, List.of(), note);
    }

    /** scope.periods ∩ 已落库分区，按 periods 顺序去重。 */
    private static List<String> intersect(List<String> periods, Set<String> stored) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String p : periods) {
            if (stored.contains(p)) out.add(p);
        }
        return new ArrayList<>(out);
    }

    // ---- 供对照断言与集成使用的查询 ----

    public boolean isBuilt() { return built; }
    public boolean isInvalid() { return invalid; }
    public List<String> getBuildErrors() { return List.copyOf(buildErrors); }
    public MetricDependencyGraph graph() { return graph; }

    /** 按指纹分组（key=fingerprint），供「8 项共用同一分母」等结构对照。 */
    public Map<String, List<FingerprintEdge>> groups() {
        Map<String, List<FingerprintEdge>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<FingerprintEdge>> e : groups.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return copy;
    }

    /** 某指纹分组的指标数（按内容指纹统计，不按名称）。 */
    public int groupSize(String fingerprint) {
        List<FingerprintEdge> g = groups.get(fingerprint);
        return g == null ? 0 : g.size();
    }

    /** 含 ≥2 项复用的分母组数量。 */
    public int reusedGroupCount() {
        int c = 0;
        for (List<FingerprintEdge> g : groups.values()) if (g.size() >= 2) c++;
        return c;
    }

    public List<FingerprintEdge> entries() { return List.copyOf(entries); }
    public FingerprintEdge entryFor(String id) { return entryById.get(id); }
    public String denominatorNodeFor(String fingerprint) { return denNodeByFingerprint.get(fingerprint); }
}
