package com.infection.metric.ui;

import com.infection.metric.metriclib.MetricStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * UI 层的口径条目只读快照。
 *
 * <p>界面不直接持有 metriclib 的 {@code MetricDefinition} 条件树，而是持有已渲染成人话的
 * 分子 / 分母 / 排除条件文本与展示字段，使三栏模型在 headless 测试中可独立构造与断言。
 * 状态复用底座 {@link MetricStatus} 枚举，保证界面徽标与状态机语义一致。</p>
 */
public final class UiMetricItem {

    private final String indicatorCode;
    private final String definitionVersion;
    private final String title;
    private final String numeratorText;
    private final String denominatorText;
    private final String exclusionsText;
    private final List<String> deptCodes;
    private final List<String> periods;
    private final List<String> dataSourceFields;
    private final String effectiveFrom;
    private final String effectiveTo;
    private final MetricStatus status;
    private final String author;
    private final String approver;
    private final String approvedAt;

    private UiMetricItem(Builder b) {
        this.indicatorCode = b.indicatorCode;
        this.definitionVersion = b.definitionVersion;
        this.title = b.title;
        this.numeratorText = b.numeratorText;
        this.denominatorText = b.denominatorText;
        this.exclusionsText = b.exclusionsText;
        this.deptCodes = List.copyOf(b.deptCodes);
        this.periods = List.copyOf(b.periods);
        this.dataSourceFields = List.copyOf(b.dataSourceFields);
        this.effectiveFrom = b.effectiveFrom;
        this.effectiveTo = b.effectiveTo;
        this.status = b.status;
        this.author = b.author;
        this.approver = b.approver;
        this.approvedAt = b.approvedAt;
    }

    public static Builder builder(String code, String version) {
        return new Builder(code, version);
    }

    public String getIndicatorCode() { return indicatorCode; }
    public String getDefinitionVersion() { return definitionVersion; }
    public String getTitle() { return title; }
    public String getNumeratorText() { return numeratorText; }
    public String getDenominatorText() { return denominatorText; }
    public String getExclusionsText() { return exclusionsText; }
    public List<String> getDeptCodes() { return deptCodes; }
    public List<String> getPeriods() { return periods; }
    public List<String> getDataSourceFields() { return dataSourceFields; }
    public String getEffectiveFrom() { return effectiveFrom; }
    public String getEffectiveTo() { return effectiveTo; }
    public MetricStatus getStatus() { return status; }
    public String getAuthor() { return author; }
    public String getApprover() { return approver; }
    public String getApprovedAt() { return approvedAt; }

    /** 联合主键 = indicator_code|definition_version。 */
    public String key() { return indicatorCode + "|" + definitionVersion; }

    /** 构建器：字段均有安全默认值，测试可只填关心的字段。 */
    public static final class Builder {
        private final String indicatorCode;
        private final String definitionVersion;
        private String title = "";
        private String numeratorText = "";
        private String denominatorText = "";
        private String exclusionsText = "";
        private List<String> deptCodes = new ArrayList<>();
        private List<String> periods = new ArrayList<>();
        private List<String> dataSourceFields = new ArrayList<>();
        private String effectiveFrom = "";
        private String effectiveTo = "";
        private MetricStatus status = MetricStatus.DRAFT;
        private String author = "";
        private String approver = "";
        private String approvedAt = "";

        private Builder(String code, String version) {
            this.indicatorCode = code;
            this.definitionVersion = version;
        }
        public Builder title(String v) { this.title = v; return this; }
        public Builder numeratorText(String v) { this.numeratorText = v; return this; }
        public Builder denominatorText(String v) { this.denominatorText = v; return this; }
        public Builder exclusionsText(String v) { this.exclusionsText = v; return this; }
        public Builder deptCodes(List<String> v) { this.deptCodes = new ArrayList<>(v); return this; }
        public Builder periods(List<String> v) { this.periods = new ArrayList<>(v); return this; }
        public Builder dataSourceFields(List<String> v) { this.dataSourceFields = new ArrayList<>(v); return this; }
        public Builder effectiveFrom(String v) { this.effectiveFrom = v; return this; }
        public Builder effectiveTo(String v) { this.effectiveTo = v; return this; }
        public Builder status(MetricStatus v) { this.status = v; return this; }
        public Builder author(String v) { this.author = v; return this; }
        public Builder approver(String v) { this.approver = v; return this; }
        public Builder approvedAt(String v) { this.approvedAt = v; return this; }
        public UiMetricItem build() { return new UiMetricItem(this); }
    }
}
