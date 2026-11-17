package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务35：三段齐全、中文段落与表格、来源引用、缺一段报错、重复渲染逐字一致。 */
class DiffReportTest {

    private static MetricDefinition oldDef() {
        return MetricDefinition.builder("MER-TL-01", "2026.0")
                .status(MetricStatus.PUBLISHED).build();
    }

    private static MetricDefinition newDef() {
        return MetricDefinition.builder("MER-TL-01", "2026.1")
                .status(MetricStatus.PUBLISHED).build();
    }

    private static DiffResult sampleDiff() {
        ConditionChange c = new ConditionChange(ConditionChange.Bucket.NUMERATOR_ADDED,
                TreePosition.NUMERATOR, "admission_record_ontime", "eq", null, "true");
        return new DiffResult(List.of(c), List.of(), List.of());
    }

    private static RecomputeComparison comparison(DiffResult diff) {
        RecomputeResult oldR = RecomputeResult.success(
                "MER-TL-01", "2026.0", "2026Q1", 200, 300, "ck-old", 5, 10);
        RecomputeResult newR = RecomputeResult.success(
                "MER-TL-01", "2026.1", "2026Q1", 240, 300, "ck-new", 5, 10);
        return new RecomputeComparison(oldDef(), newDef(), "2026Q1", oldR, newR, diff);
    }

    @Test
    void 三段齐全且每段带数字与来源引用() {
        DiffReportRenderer r = new DiffReportRenderer();
        RecomputeComparison cmp = comparison(sampleDiff());

        List<DiffReportRenderer.Segment> segs = r.segments(cmp);
        assertEquals(3, segs.size());
        assertEquals("分子差", segs.get(0).getName());
        assertEquals("分母差", segs.get(1).getName());
        assertEquals("口径差", segs.get(2).getName());
        assertEquals(40, segs.get(0).getNumber(), "分子差数字");
        assertEquals(0, segs.get(1).getNumber(), "分母差数字");
        assertEquals(1, segs.get(2).getNumber(), "口径差 = 变更条目数");

        String paragraph = r.renderParagraphs(cmp);
        assertTrue(paragraph.contains("一、分子差"));
        assertTrue(paragraph.contains("二、分母差"));
        assertTrue(paragraph.contains("三、口径差"));
        assertTrue(paragraph.contains("200"), "旧版分子");
        assertTrue(paragraph.contains("240"), "新版分子");
        assertTrue(paragraph.contains("+40"), "分子差数字");
        assertTrue(paragraph.contains("变更条目 1 条"), "来源引用含变更条目");
        assertTrue(paragraph.contains("MER-TL-01"), "来源引用含口径编码");
        assertTrue(paragraph.contains("2026.0") && paragraph.contains("2026.1"), "来源引用含新旧版本");

        String table = r.renderTable(cmp);
        assertTrue(table.contains("分子差"));
        assertTrue(table.contains("分母差"));
        assertTrue(table.contains("口径差"));
        assertTrue(table.contains("admission_record_ontime"), "表格含条件级差异明细");

        JsonNode json = r.renderJson(cmp);
        assertEquals(3, json.path("segments").size());
        assertEquals("分子差", json.path("segments").get(0).path("name").asText());
    }

    @Test
    void 缺一段报错而非留空() {
        DiffReportRenderer r = new DiffReportRenderer();

        // 缺口径差段
        RecomputeComparison noDiff = comparison(null);
        AppException ex1 = assertThrows(AppException.class, () -> r.renderParagraphs(noDiff));
        assertEquals("E_RENDER_001", ex1.getCode());
        assertTrue(ex1.getMessage().contains("口径差"));

        // 缺分子 / 分母段（新版复算失败，数字为 null）
        RecomputeResult oldR = RecomputeResult.success(
                "MER-TL-01", "2026.0", "2026Q1", 200, 300, "ck-old", 5, 10);
        RecomputeResult failed = RecomputeResult.failure(
                "MER-TL-01", "2026.1", "2026Q1", "ck-new", "取数", "数据源不可达");
        RecomputeComparison noNumber = new RecomputeComparison(
                oldDef(), newDef(), "2026Q1", oldR, failed, sampleDiff());
        AppException ex2 = assertThrows(AppException.class, () -> r.renderTable(noNumber));
        assertEquals("E_RENDER_001", ex2.getCode());
        assertNull(failed.getDenominator(), "失败结果分母为 null，绝不按 0 留空渲染");
    }

    @Test
    void 同一输入两次渲染结果逐字一致() {
        DiffReportRenderer r = new DiffReportRenderer();
        RecomputeComparison cmp = comparison(sampleDiff());

        assertEquals(r.renderParagraphs(cmp), r.renderParagraphs(cmp));
        assertEquals(r.renderTable(cmp), r.renderTable(cmp));
        assertEquals(r.renderJson(cmp).toString(), r.renderJson(cmp).toString());

        RecomputeComparison same = comparison(sampleDiff());
        assertEquals(r.renderParagraphs(cmp), r.renderParagraphs(same),
                "等价输入渲染结果逐字一致（不含时间戳类字段）");
        assertEquals(r.renderTable(cmp), r.renderTable(same));
    }
}
