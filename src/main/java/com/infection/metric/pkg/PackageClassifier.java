package com.infection.metric.pkg;

import com.infection.metric.common.Hashes;
import com.infection.metric.expr.Canonicalizer;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上级口径包三清单分类：逐条对齐后分出「新增 / 实质口径变更 / 本院例外」。
 *
 * <p>对齐语义：按 指标编码 + 名称 对齐上级条目与本院已发布口径
 * （本院库以 {@code List<MetricDefinition>} 注入）。无法唯一对齐（同编码多版本、
 * 或编码命中但名称不符）的条目标「待人工判定」，绝不自动归类。</p>
 *
 * <p>「实质口径变更」判定复用 {@link Canonicalizer#fingerprint(ConditionTree)}：
 * 两侧都有且分子 / 分母 canonical form 指纹不同才成立，并逐片叶子列出条件级差异；
 * 不靠名称、也不靠整条版本字段。「本院例外」为上级无对应、本院自有的口径，
 * 本院口径不因导入而失效。</p>
 */
public final class PackageClassifier {

    private final List<MetricDefinition> localLibrary;
    private final boolean publishedOnly;

    /** 默认只对齐本院已发布（PUBLISHED）口径。 */
    public PackageClassifier(List<MetricDefinition> localLibrary) {
        this(localLibrary, true);
    }

    /** publishedOnly=false 时把注入列表全部视为本院库（便于离线/契约测试）。 */
    public PackageClassifier(List<MetricDefinition> localLibrary, boolean publishedOnly) {
        this.localLibrary = localLibrary == null ? List.of() : List.copyOf(localLibrary);
        this.publishedOnly = publishedOnly;
    }

    /** 对单个包版本做逐条对齐分类。 */
    public ThreeLists classify(ParsedPackage pkg) {
        String pv = pkg.getPackageVersion();

        // 本院已发布口径按编码分组（保留多版本并存结构，用于「无法唯一对齐」判定）
        Map<String, List<MetricDefinition>> byCode = new LinkedHashMap<>();
        for (MetricDefinition d : localLibrary) {
            if (publishedOnly && d.getStatus() != MetricStatus.PUBLISHED) continue;
            byCode.computeIfAbsent(d.getIndicatorCode(), k -> new ArrayList<>()).add(d);
        }
        Set<String> upperCodes = new LinkedHashSet<>();
        for (PackageEntry e : pkg.getEntries()) {
            if (!e.getIndicatorCode().isBlank()) upperCodes.add(e.getIndicatorCode());
        }

        List<ClassifiedEntry> newItems = new ArrayList<>();
        List<ClassifiedEntry> changedItems = new ArrayList<>();
        List<ClassifiedEntry> manualReview = new ArrayList<>();
        List<ClassifiedEntry> unchanged = new ArrayList<>();

        for (PackageEntry e : pkg.getEntries()) {
            List<MetricDefinition> codeMatches = byCode.getOrDefault(e.getIndicatorCode(), List.of());
            if (codeMatches.isEmpty()) {
                // 上级有、本院无 → 新增
                newItems.add(new ClassifiedEntry(
                        ClassifiedEntry.Category.NEW, e.getEntryNo(), e.getIndicatorCode(), e.getIndicatorName(),
                        "上级口径包 " + pv + " 条目 " + e.getEntryNo() + "（编码 " + e.getIndicatorCode()
                                + "）在本院已发布口径库中无对应编码 → 新增",
                        List.of(), "", "上级有、本院无"));
                continue;
            }
            List<MetricDefinition> exact = new ArrayList<>();
            for (MetricDefinition d : codeMatches) {
                if (d.getTitle().trim().equals(e.getIndicatorName().trim())) exact.add(d);
            }
            if (exact.size() != 1) {
                // 编码命中但名称不符，或同名多版本 → 无法唯一对齐，不自动归类
                manualReview.add(new ClassifiedEntry(
                        ClassifiedEntry.Category.MANUAL_REVIEW, e.getEntryNo(), e.getIndicatorCode(), e.getIndicatorName(),
                        "上级条目 " + e.getEntryNo() + " 按 指标编码+名称 无法唯一对齐（本院编码候选 "
                                + codeMatches.size() + " 个，名称匹配 " + exact.size() + " 个）→ 待人工判定，不自动归类",
                        List.of(), "", "需人工确认对齐关系后再归类"));
                continue;
            }
            MetricDefinition local = exact.get(0);
            String upNum = fp(e.getNumeratorTree(), e.getNumerator());
            String upDen = fp(e.getDenominatorTree(), e.getDenominator());
            String loNum = fp(local.getNumerator(), null);
            String loDen = fp(local.getDenominator(), null);
            boolean same = upNum.equals(loNum) && upDen.equals(loDen);
            if (same) {
                unchanged.add(new ClassifiedEntry(
                        ClassifiedEntry.Category.UNCHANGED, e.getEntryNo(), e.getIndicatorCode(), e.getIndicatorName(),
                        "上级条目 " + e.getEntryNo() + " 与本院口径 " + local.key()
                                + " 对齐且 canonical form 指纹相同 → 无实质变更",
                        List.of(), local.getDefinitionVersion(), "口径等价，无需变更"));
            } else {
                List<String> diffs = conditionDiffs(e, local);
                changedItems.add(new ClassifiedEntry(
                        ClassifiedEntry.Category.CHANGED, e.getEntryNo(), e.getIndicatorCode(), e.getIndicatorName(),
                        "上级条目 " + e.getEntryNo() + " 与本院口径 " + local.key()
                                + " 按 指标编码+名称 对齐；canonical form 指纹不同 → 实质口径变更",
                        diffs, local.getDefinitionVersion(), "分子/分母口径实质变化，需逐条确认"));
            }
        }

        // 本院例外：上级包无对应编码、本院自有；本院口径不因导入失效
        List<ClassifiedEntry> localExceptions = new ArrayList<>();
        for (Map.Entry<String, List<MetricDefinition>> en : byCode.entrySet()) {
            if (upperCodes.contains(en.getKey())) continue;
            List<String> versions = new ArrayList<>();
            String title = "";
            for (MetricDefinition d : en.getValue()) {
                versions.add(d.getDefinitionVersion());
                if (title.isEmpty()) title = d.getTitle();
            }
            localExceptions.add(new ClassifiedEntry(
                    ClassifiedEntry.Category.LOCAL_EXCEPTION, "(本院口径)", en.getKey(), title,
                    "本院已发布口径 " + en.getKey() + "（版本 " + String.join(",", versions)
                            + "）在上级包中无对应编码 → 本院例外",
                    List.of(), String.join(",", versions),
                    "本院自有口径，不因导入失效；导入上级包不改变其状态与版本"));
        }

        return new ThreeLists(pv, newItems, changedItems, localExceptions, manualReview, unchanged);
    }

    /** 分子 / 分母分别取 canonical form 指纹，无结构化条件树时退回规范化文本哈希。 */
    private static String fp(ConditionTree tree, String text) {
        if (tree != null) return Canonicalizer.fingerprint(tree);
        return Hashes.sha256(text == null ? "" : text.trim().replaceAll("\\s+", " "));
    }

    /** 条件级差异：逐片叶子的 canonical 形式做集合差，上级新增 / 本院移除分列。 */
    private static List<String> conditionDiffs(PackageEntry up, MetricDefinition local) {
        Set<String> upSet = new LinkedHashSet<>();
        upSet.addAll(up.numeratorLeafForms());
        upSet.addAll(up.denominatorLeafForms());
        Set<String> loSet = new LinkedHashSet<>();
        loSet.addAll(leafForms(local.getNumerator(), "分子"));
        loSet.addAll(leafForms(local.getDenominator(), "分母"));

        List<String> diffs = new ArrayList<>();
        for (String s : upSet) if (!loSet.contains(s)) diffs.add("上级新增条件：" + s);
        for (String s : loSet) if (!upSet.contains(s)) diffs.add("本院移除条件：" + s);
        if (diffs.isEmpty()) {
            diffs.add("分子/分母 canonical form 指纹不同（结构差异），条件级叶子集合一致");
        }
        return diffs;
    }

    private static List<String> leafForms(ConditionTree tree, String tag) {
        List<String> out = new ArrayList<>();
        if (tree != null) {
            for (ConditionTree leaf : tree.leaves()) {
                out.add(tag + ":" + Canonicalizer.canonicalNode(leaf).toString());
            }
        }
        return out;
    }
}
