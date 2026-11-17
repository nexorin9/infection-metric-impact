package com.infection.metric.ui;

/** 提交复核的三项前置检查：字段对账通过 / 依赖图无环 / 差异已逐条确认。 */
public final class PublishPreCheck {

    private final boolean fieldReconciled;
    private final boolean graphAcyclic;
    private final boolean diffConfirmed;

    private PublishPreCheck(boolean fieldReconciled, boolean graphAcyclic, boolean diffConfirmed) {
        this.fieldReconciled = fieldReconciled;
        this.graphAcyclic = graphAcyclic;
        this.diffConfirmed = diffConfirmed;
    }

    public static PublishPreCheck allPassed() {
        return new PublishPreCheck(true, true, true);
    }

    public static PublishPreCheck of(boolean fieldReconciled, boolean graphAcyclic, boolean diffConfirmed) {
        return new PublishPreCheck(fieldReconciled, graphAcyclic, diffConfirmed);
    }

    public boolean isFieldReconciled() { return fieldReconciled; }
    public boolean isGraphAcyclic() { return graphAcyclic; }
    public boolean isDiffConfirmed() { return diffConfirmed; }
}
