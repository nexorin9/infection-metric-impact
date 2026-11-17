package com.infection.metric.recompute;

import com.infection.metric.common.DataRecord;

import java.util.List;

/**
 * 复算取数的最小数据载体（本包内自定义，不跨模块硬依赖）：
 * 一个季度的分子记录集与分母记录集。分子与分母可来自不同数据源
 * （如院感明细作分子、出院记录作分母），由注入的 {@link RecordSupplier} 决定。
 */
public final class QuarterRecords {

    private final List<DataRecord> numerator;
    private final List<DataRecord> denominator;

    public QuarterRecords(List<DataRecord> numerator, List<DataRecord> denominator) {
        this.numerator = numerator == null ? List.of() : List.copyOf(numerator);
        this.denominator = denominator == null ? List.of() : List.copyOf(denominator);
    }

    /** 分子与分母共用同一批记录。 */
    public static QuarterRecords of(List<DataRecord> both) {
        return new QuarterRecords(both, both);
    }

    /** 分子、分母分别取数。 */
    public static QuarterRecords of(List<DataRecord> numerator, List<DataRecord> denominator) {
        return new QuarterRecords(numerator, denominator);
    }

    public List<DataRecord> getNumerator() { return numerator; }
    public List<DataRecord> getDenominator() { return denominator; }
}
