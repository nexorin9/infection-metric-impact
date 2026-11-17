package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.metriclib.MetricDefinition;

/**
 * 双版本对拍结果（任务8）：同一季度用新旧两版口径各复算一遍，
 * 产出分子差 / 分母差 / 口径差三段，供 {@link DiffReportRenderer} 组装差异说明。
 */
public final class RecomputeComparison {

    private final MetricDefinition oldDef;
    private final MetricDefinition newDef;
    private final String quarter;
    private final RecomputeResult oldResult;
    private final RecomputeResult newResult;
    private final DiffResult definitionDiff;

    public RecomputeComparison(MetricDefinition oldDef, MetricDefinition newDef, String quarter,
                              RecomputeResult oldResult, RecomputeResult newResult,
                              DiffResult definitionDiff) {
        this.oldDef = oldDef;
        this.newDef = newDef;
        this.quarter = quarter;
        this.oldResult = oldResult;
        this.newResult = newResult;
        this.definitionDiff = definitionDiff;
    }

    public MetricDefinition getOldDef() { return oldDef; }
    public MetricDefinition getNewDef() { return newDef; }
    public String getQuarter() { return quarter; }
    public RecomputeResult getOldResult() { return oldResult; }
    public RecomputeResult getNewResult() { return newResult; }
    public DiffResult getDefinitionDiff() { return definitionDiff; }

    public String getIndicatorCode() {
        return oldDef == null ? "" : oldDef.getIndicatorCode();
    }

    public String getOldVersion() {
        return oldDef == null ? "" : oldDef.getDefinitionVersion();
    }

    public String getNewVersion() {
        return newDef == null ? "" : newDef.getDefinitionVersion();
    }

    /** 分子差 = 新版分子 - 旧版分子；任一段缺失（失败）时返回 null。 */
    public Integer getNumeratorDelta() {
        if (!numbersAvailable()) return null;
        return newResult.getNumerator() - oldResult.getNumerator();
    }

    /** 分母差 = 新版分母 - 旧版分母。 */
    public Integer getDenominatorDelta() {
        if (!numbersAvailable()) return null;
        return newResult.getDenominator() - oldResult.getDenominator();
    }

    /** 三段数据是否齐全：两次复算都成功且口径差段存在。缺一段不得留空渲染。 */
    public boolean hasAllSegments() {
        return definitionDiff != null && numbersAvailable();
    }

    private boolean numbersAvailable() {
        return oldResult != null && oldResult.hasValidNumbers()
                && newResult != null && newResult.hasValidNumbers();
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("indicator_code", getIndicatorCode());
        o.put("old_version", getOldVersion());
        o.put("new_version", getNewVersion());
        o.put("quarter", quarter);
        if (getNumeratorDelta() == null) o.putNull("numerator_delta");
        else o.put("numerator_delta", getNumeratorDelta());
        if (getDenominatorDelta() == null) o.putNull("denominator_delta");
        else o.put("denominator_delta", getDenominatorDelta());
        if (definitionDiff == null) o.putNull("definition_diff");
        else o.set("definition_diff", definitionDiff.toJson());
        return o;
    }
}
