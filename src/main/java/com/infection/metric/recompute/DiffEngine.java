package com.infection.metric.recompute;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.expr.Canonicalizer;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.expr.ExpressionParser;
import com.infection.metric.metriclib.MetricDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 两版口径结构化三桶差异引擎（任务7，M4-A 核心差异化点）。
 *
 * <p>流水线严格按「先解析 → 再归一化 → 再做集合差 → 输出三桶」：</p>
 * <ol>
 *   <li><b>解析</b>：把口径拆成条件级条目（分子树 / 分母树 / 排除树 / scope 字段），
 *       或把表达式文本经 {@link ExpressionParser} 解析成条件树；</li>
 *   <li><b>归一化</b>：canonical 取值（字段顺序无关、集合去重并排序、in/not_in 元素顺序无关），
 *       收口成稳定的条目集合；</li>
 *   <li><b>集合差</b>：旧集合与新集合做差，输出
 *       {@code numeratorAdded} / {@code exclusionAdded} / {@code removed} 三桶。</li>
 * </ol>
 *
 * <p>{@link #diffWithoutNormalization(MetricDefinition, MetricDefinition)} 是<b>对照通道</b>：
 * 跳过归一化、按原始顺序逐位置比较。用「元素顺序不同但集合相同」的旧 / 新两版口径时，
 * 归一化通道无差异、对照通道有差异，从而把归一化次序可被测试证出。</p>
 */
public final class DiffEngine {

    public DiffResult diff(MetricDefinition oldDef, MetricDefinition newDef) {
        require(oldDef, newDef);
        return pipeline(toSpec(oldDef), toSpec(newDef), true);
    }

    /** 对照通道：不归一化，按原始顺序逐位置比较（用于证出归一化次序）。 */
    public DiffResult diffWithoutNormalization(MetricDefinition oldDef, MetricDefinition newDef) {
        require(oldDef, newDef);
        return pipeline(toSpec(oldDef), toSpec(newDef), false);
    }

    /** 表达式文本入口：先解析（ExpressionParser）→ 再归一化 → 再集合差。 */
    public DiffResult diffExpressions(String oldExpression, String newExpression) {
        return expressionPipeline(oldExpression, newExpression, true);
    }

    /** 表达式文本对照通道：跳过归一化。 */
    public DiffResult diffExpressionsWithoutNormalization(String oldExpression, String newExpression) {
        return expressionPipeline(oldExpression, newExpression, false);
    }

    private DiffResult expressionPipeline(String oldExpression, String newExpression, boolean normalized) {
        if (oldExpression == null || newExpression == null) {
            throw new AppException(ErrorCodes.E_PARSE_003,
                    "两版口径表达式不可为空，无法比较差异");
        }
        // ① 解析
        ConditionTree oldTree = ExpressionParser.parse(oldExpression);
        ConditionTree newTree = ExpressionParser.parse(newExpression);
        Spec os = new Spec();
        os.numerator = oldTree;
        Spec ns = new Spec();
        ns.numerator = newTree;
        return pipeline(os, ns, normalized);
    }

    // ---------------------------------------------------------------- 流水线

    private DiffResult pipeline(Spec oldSpec, Spec newSpec, boolean normalized) {
        // ① 解析：把两版口径拆成条件级条目
        List<ConditionEntry> oldParsed = parse(oldSpec, normalized);
        List<ConditionEntry> newParsed = parse(newSpec, normalized);

        // ② 归一化：canonical 取值 + 集合去重并排序（对照通道跳过此步，保留原始顺序）
        List<ConditionEntry> oldFinal = normalized ? normalize(oldParsed) : oldParsed;
        List<ConditionEntry> newFinal = normalized ? normalize(newParsed) : newParsed;

        // ③ 集合差 / 逐位置比较 → 三桶
        return normalized ? setDifference(oldFinal, newFinal)
                          : positionalDifference(oldFinal, newFinal);
    }

    /** ① 解析：拆解口径为条件级条目；normalized 决定取值是否采用 canonical form。 */
    private List<ConditionEntry> parse(Spec s, boolean normalized) {
        List<ConditionEntry> out = new ArrayList<>();
        addTree(out, s.numerator, TreePosition.NUMERATOR, normalized);
        addTree(out, s.denominator, TreePosition.DENOMINATOR, normalized);
        for (ConditionTree e : s.exclusions) {
            addTree(out, e, TreePosition.EXCLUSION, normalized);
        }
        out.add(new ConditionEntry(TreePosition.SCOPE_DEPT, "scope.deptCodes", "in",
                joined(s.deptCodes, normalized)));
        out.add(new ConditionEntry(TreePosition.SCOPE_PERIOD, "scope.periods", "in",
                joined(s.periods, normalized)));
        out.add(new ConditionEntry(TreePosition.EFFECTIVE_FROM, "effective_from", "eq",
                s.effectiveFrom == null ? "" : s.effectiveFrom.trim()));
        out.add(new ConditionEntry(TreePosition.EFFECTIVE_TO, "effective_to", "eq",
                s.effectiveTo == null ? "" : s.effectiveTo.trim()));
        return out;
    }

    private void addTree(List<ConditionEntry> out, ConditionTree tree,
                         TreePosition position, boolean normalized) {
        if (tree == null) return;
        ConditionTree src = normalized ? Canonicalizer.normalize(tree) : tree;
        for (ConditionTree leaf : src.leaves()) {
            out.add(new ConditionEntry(position, leaf.getField().trim(), leaf.getCmp(),
                    String.valueOf(leaf.getValue())));
        }
    }

    /** ② 归一化收口：以条目集合去重并排序，消除字段顺序与列表顺序差异。 */
    private List<ConditionEntry> normalize(List<ConditionEntry> parsed) {
        Set<ConditionEntry> set = new LinkedHashSet<>(parsed);
        List<ConditionEntry> out = new ArrayList<>(set);
        out.sort(Comparator.comparing(ConditionEntry::evidenceRef));
        return out;
    }

    /** ③ 集合差：旧集合 - 新集合 = 移除；新集合 - 旧集合 = 新增（按位置分桶）。 */
    private DiffResult setDifference(List<ConditionEntry> oldSet, List<ConditionEntry> newSet) {
        LinkedHashSet<ConditionEntry> o = new LinkedHashSet<>(oldSet);
        LinkedHashSet<ConditionEntry> n = new LinkedHashSet<>(newSet);

        List<ConditionChange> removed = new ArrayList<>();
        List<ConditionChange> added = new ArrayList<>();
        for (ConditionEntry e : o) {
            if (!n.contains(e)) {
                removed.add(new ConditionChange(ConditionChange.Bucket.REMOVED,
                        e.getPosition(), e.getField(), e.getCmp(), e.getValue(), null));
            }
        }
        for (ConditionEntry e : n) {
            if (!o.contains(e)) {
                added.add(new ConditionChange(bucketOf(e.getPosition()),
                        e.getPosition(), e.getField(), e.getCmp(), null, e.getValue()));
            }
        }
        return split(added, removed);
    }

    /** 对照通道：按原始顺序逐位置比较，顺序 / 元素次序不同即产生差异。 */
    private DiffResult positionalDifference(List<ConditionEntry> oldEntries,
                                             List<ConditionEntry> newEntries) {
        List<ConditionChange> removed = new ArrayList<>();
        List<ConditionChange> added = new ArrayList<>();
        int n = Math.max(oldEntries.size(), newEntries.size());
        for (int i = 0; i < n; i++) {
            ConditionEntry o = i < oldEntries.size() ? oldEntries.get(i) : null;
            ConditionEntry e = i < newEntries.size() ? newEntries.get(i) : null;
            if (o == null && e == null) continue;
            if (o == null) {
                added.add(new ConditionChange(bucketOf(e.getPosition()),
                        e.getPosition(), e.getField(), e.getCmp(), null, e.getValue()));
            } else if (e == null) {
                removed.add(new ConditionChange(ConditionChange.Bucket.REMOVED,
                        o.getPosition(), o.getField(), o.getCmp(), o.getValue(), null));
            } else if (!o.equals(e)) {
                removed.add(new ConditionChange(ConditionChange.Bucket.REMOVED,
                        o.getPosition(), o.getField(), o.getCmp(), o.getValue(), null));
                added.add(new ConditionChange(bucketOf(e.getPosition()),
                        e.getPosition(), e.getField(), e.getCmp(), null, e.getValue()));
            }
        }
        return split(added, removed);
    }

    private static DiffResult split(List<ConditionChange> added, List<ConditionChange> removed) {
        List<ConditionChange> numeratorAdded = new ArrayList<>();
        List<ConditionChange> exclusionAdded = new ArrayList<>();
        for (ConditionChange c : added) {
            if (c.getBucket() == ConditionChange.Bucket.EXCLUSION_ADDED) {
                exclusionAdded.add(c);
            } else {
                numeratorAdded.add(c);
            }
        }
        return new DiffResult(numeratorAdded, exclusionAdded, removed);
    }

    private static ConditionChange.Bucket bucketOf(TreePosition position) {
        return position == TreePosition.EXCLUSION
                ? ConditionChange.Bucket.EXCLUSION_ADDED
                : ConditionChange.Bucket.NUMERATOR_ADDED;
    }

    private static String joined(List<String> values, boolean normalized) {
        if (values == null || values.isEmpty()) return "";
        if (normalized) {
            return String.join(",", new TreeSet<>(values));
        }
        return String.join(",", values);
    }

    private static void require(MetricDefinition oldDef, MetricDefinition newDef) {
        if (oldDef == null || newDef == null) {
            throw new AppException(ErrorCodes.E_PARSE_003,
                    "两版口径差异比较失败：旧版或新版口径为空");
        }
    }

    private static Spec toSpec(MetricDefinition d) {
        Spec s = new Spec();
        s.numerator = d.getNumerator();
        s.denominator = d.getDenominator();
        s.exclusions = d.getExclusions();
        s.deptCodes = d.getDeptCodes();
        s.periods = d.getPeriods();
        s.effectiveFrom = d.getEffectiveFrom();
        s.effectiveTo = d.getEffectiveTo();
        return s;
    }

    /** 差异引擎内部的口径视图（解析产物）。 */
    private static final class Spec {
        private ConditionTree numerator;
        private ConditionTree denominator;
        private List<ConditionTree> exclusions = List.of();
        private List<String> deptCodes = List.of();
        private List<String> periods = List.of();
        private String effectiveFrom = "";
        private String effectiveTo = "";
    }
}
