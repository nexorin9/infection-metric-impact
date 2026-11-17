package com.infection.metric.ui;

/** 依赖树中的一条边：谁依赖谁、按哪个口径指纹匹配上、人话依据与所处深度。 */
public final class DependencyEdgeView {

    private final String fromCode;
    private final String toCode;
    private final String fingerprint;
    private final String evidence;
    private final int depth;

    public DependencyEdgeView(String fromCode, String toCode, String fingerprint,
                              String evidence, int depth) {
        this.fromCode = fromCode == null ? "" : fromCode;
        this.toCode = toCode == null ? "" : toCode;
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        this.evidence = evidence == null ? "" : evidence;
        this.depth = depth;
    }

    public String getFromCode() { return fromCode; }
    public String getToCode() { return toCode; }
    public String getFingerprint() { return fingerprint; }
    public String getEvidence() { return evidence; }
    public int getDepth() { return depth; }
}
