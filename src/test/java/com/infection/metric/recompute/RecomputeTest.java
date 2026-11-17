package com.infection.metric.recompute;

import com.infection.metric.common.AppException;
import com.infection.metric.common.DataRecord;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 任务8：内容哈希缓存、重复复算一致、失败显式失败、未发布口径 E_RECOMPUTE_002、双版本对拍三段。 */
class RecomputeTest {

    private static DataRecord rec(String dept, boolean gradeA) {
        return DataRecord.builder()
                .put("quarter", "2026Q1")
                .put("dept_code", dept)
                .put("grade_a", gradeA ? "true" : "false")
                .build();
    }

    private static final List<DataRecord> DATA = List.of(
            rec("0301", true), rec("0301", false), rec("0302", true), rec("0302", false));

    private static MetricDefinition def(String version, ConditionTree numerator, MetricStatus status) {
        return MetricDefinition.builder("MER-TQ-01", version)
                .numerator(numerator)
                .denominator(ConditionTree.leaf("quarter", "eq", "2026Q1"))
                .status(status)
                .build();
    }

    private static MetricDefinition published(String version) {
        return def(version, ConditionTree.leaf("dept_code", "eq", "0301"), MetricStatus.PUBLISHED);
    }

    @Test
    void 同口径重复复算命中缓存不重复取数() {
        AtomicInteger fetches = new AtomicInteger();
        RecordSupplier supplier = q -> {
            fetches.incrementAndGet();
            return QuarterRecords.of(DATA);
        };
        RecomputeEngine engine = new RecomputeEngine(supplier, "ds-v1");
        MetricDefinition d = published("2026.1");

        RecomputeResult r1 = engine.recompute(d, "2026Q1");
        assertTrue(r1.isSuccess());
        assertEquals(2, r1.getNumerator().intValue(), "两条 dept=0301 命中分子");
        assertEquals(4, r1.getDenominator().intValue(), "四条记录命中分母");
        assertEquals(d.contentFingerprint(), r1.getCacheKey(), "缓存键 = 口径规范化内容指纹");
        assertEquals(1, fetches.get());

        RecomputeResult r2 = engine.recompute(d, "2026Q1");
        assertEquals(1, fetches.get(), "命中缓存后取数次数不再增加");
        assertTrue(r2.isCacheHit());
        assertEquals(r1.getNumerator(), r2.getNumerator());
        assertEquals(r1.getDenominator(), r2.getDenominator());
    }

    @Test
    void 同输入两次复算数字逐项相等() {
        RecordSupplier s1 = q -> QuarterRecords.of(DATA);
        RecordSupplier s2 = q -> QuarterRecords.of(DATA);
        RecomputeEngine e1 = new RecomputeEngine(s1, "ds-v1");
        RecomputeEngine e2 = new RecomputeEngine(s2, "ds-v1");
        MetricDefinition d = published("2026.1");

        RecomputeResult a = e1.recompute(d, "2026Q1");
        RecomputeResult b = e2.recompute(d, "2026Q1");
        RecomputeResult c = e1.recompute(d, "2026Q1"); // 命中缓存

        assertEquals(a.getNumerator(), b.getNumerator());
        assertEquals(a.getDenominator(), b.getDenominator());
        assertEquals(a.getCacheKey(), b.getCacheKey());
        assertEquals(a.getNumerator(), c.getNumerator());
        assertEquals(a.getDenominator(), c.getDenominator());
        assertEquals(e1.getSummary().reproducibilityKey(), e2.getSummary().reproducibilityKey(),
                "同输入的运行元数据指纹相同");
    }

    @Test
    void 取数失败与字段缺失显式失败不按0计入分母() {
        MetricDefinition d = published("2026.1");

        RecordSupplier failing = q -> {
            throw new IllegalStateException("数据源不可达");
        };
        RecomputeEngine e = new RecomputeEngine(failing, "ds-v1");
        RecomputeResult r = e.recompute(d, "2026Q1");
        assertTrue(r.isFailed(), "取数失败应显式失败");
        assertNull(r.getNumerator());
        assertNull(r.getDenominator(), "失败不得按 0 计入分母");
        assertEquals("取数", r.getFailedStep());
        assertTrue(r.getFailureReason().contains("数据源不可达"));
        assertFalse(e.getSummary().getFailedSteps().isEmpty(), "失败步骤写入运行摘要");
        assertEquals("MER-TQ-01", e.getSummary().getFailedSteps().get(0).getIndicatorCode());

        AppException strict = assertThrows(AppException.class,
                () -> new RecomputeEngine(failing, "ds-v1").recomputeOrThrow(d, "2026Q1"));
        assertEquals(ErrorCodes.E_RECOMPUTE_001, strict.getCode());

        RecordSupplier missing = q -> QuarterRecords.of(
                List.of(DataRecord.builder().put("quarter", "2026Q1").build()));
        RecomputeResult r2 = new RecomputeEngine(missing, "ds-v1").recompute(d, "2026Q1");
        assertTrue(r2.isFailed(), "字段缺失显式失败，不按 false 处理");
        assertNull(r2.getDenominator());
    }

    @Test
    void 未发布口径不可复算_E_RECOMPUTE_002() {
        MetricDefinition draft = def("2026.1",
                ConditionTree.leaf("dept_code", "eq", "0301"), MetricStatus.DRAFT);
        RecomputeEngine e = new RecomputeEngine(q -> QuarterRecords.of(DATA), "ds-v1");

        AppException ex = assertThrows(AppException.class, () -> e.recompute(draft, "2026Q1"));
        assertEquals(ErrorCodes.E_RECOMPUTE_002, ex.getCode());
        assertTrue(ex.render().contains("MER-TQ-01"));
        assertTrue(e.getSummary().getUnpublishedDefinitions().contains("MER-TQ-01|2026.1"),
                "未发布口径写入运行摘要清单");
    }

    @Test
    void 双版本对拍产出分子差分母差口径差三段且数字可复算() {
        RecomputeEngine e = new RecomputeEngine(q -> QuarterRecords.of(DATA), "ds-v1");
        MetricDefinition oldDef = published("2026.0");
        MetricDefinition newDef = def("2026.1", ConditionTree.and(
                ConditionTree.leaf("dept_code", "eq", "0301"),
                ConditionTree.leaf("grade_a", "eq", "true")), MetricStatus.PUBLISHED);

        RecomputeComparison c = e.compare(oldDef, newDef, "2026Q1");
        assertTrue(c.hasAllSegments());
        assertEquals(-1, c.getNumeratorDelta().intValue(), "新版分子 1 - 旧版分子 2 = -1");
        assertEquals(0, c.getDenominatorDelta().intValue());
        assertNotNull(c.getDefinitionDiff());
        assertFalse(c.getDefinitionDiff().isEmpty(), "口径差段含条件级差异");
        assertEquals("grade_a", c.getDefinitionDiff().getNumeratorAdded().get(0).getField());

        RecomputeComparison c2 = e.compare(oldDef, newDef, "2026Q1");
        assertEquals(c.getNumeratorDelta(), c2.getNumeratorDelta(), "重复对拍数字一致");
        assertEquals(c.getDenominatorDelta(), c2.getDenominatorDelta());
        assertEquals(c.getDefinitionDiff().total(), c2.getDefinitionDiff().total());
    }

    @Test
    void 运行摘要记录复算结果与期间元数据() {
        RecomputeEngine e = new RecomputeEngine(q -> QuarterRecords.of(DATA), "ds-v1");
        e.recompute(published("2026.1"), "2026Q1");
        RunSummary s = e.getSummary();
        assertEquals("ds-v1", s.getDataSourceVersion());
        assertTrue(s.getPeriods().contains("2026Q1"), "复算期间记入运行元数据");
        assertTrue(s.getDefinitionVersions().contains("MER-TQ-01|2026.1"));
        assertEquals(1, s.getResults().size());
        assertEquals(4, s.getResults().get(0).getDenominator().intValue());
    }
}
