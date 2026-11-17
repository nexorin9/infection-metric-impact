package com.infection.metric.recompute;

import com.infection.metric.common.AtomicFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 任务9：规则化合成数据规模、108 次求值性能、以 oracle 为准逐项断言、年度分区落盘与差异定位。 */
class ScalePerfTest {

    @Test
    void 规模性能与oracle逐项一致(@TempDir Path tmp) {
        SyntheticDataset ds = SyntheticDataset.load();
        assertEquals(4, ds.quarters().size(), "4 个季度");
        assertEquals(1200, ds.encountersAll().size(), "4 × 300 条出院记录");
        assertEquals(180, ds.infectionsAll().size(), "4 × 45 条院感明细");
        assertEquals(39, ds.indicators().size(), "27 官方 + 12 草稿");
        assertEquals(27, ds.officialCount());
        assertEquals(12, ds.draftCount());
        assertEquals(156, ds.oracleRowCount(), "39 口径 × 4 季度");

        // 27 项官方指标 × 4 季度 = 108 次求值，单次 JVM ≤ 10 秒
        long start = System.nanoTime();
        List<RecomputeResult> official = ds.recomputeOfficial();
        long ms = (System.nanoTime() - start) / 1_000_000L;
        assertEquals(108, official.size(), "27 官方 × 4 季度 = 108 次求值");
        assertTrue(ms <= 10_000, "108 次求值应在单次 JVM 内 ≤ 10 秒，实测 " + ms + "ms");

        // 以 oracle 为准逐项断言复算结果一致（分子与分母逐条相等）
        for (RecomputeResult r : official) {
            SyntheticDataset.ExpectedValue ev = ds.oracle(r.getIndicatorCode(), r.getQuarter());
            assertNotNull(ev, r.getIndicatorCode() + "@" + r.getQuarter() + " 应有 oracle 期望值");
            assertTrue(r.isSuccess(), r.getIndicatorCode() + "@" + r.getQuarter()
                    + " 复算应成功：" + r.getFailureReason());
            assertEquals(ev.getNumerator(), r.getNumerator().intValue(),
                    r.getIndicatorCode() + "@" + r.getQuarter() + " 分子应等于 oracle");
            assertEquals(ev.getDenominator(), r.getDenominator().intValue(),
                    r.getIndicatorCode() + "@" + r.getQuarter() + " 分母应等于 oracle");
        }

        RunSummary summary = ds.recomputeAll(tmp, 2026);
        assertTrue(summary.getFailedSteps().isEmpty(),
                "全量 156 项复算结果与 oracle 逐项一致，运行摘要应无差异");
    }

    @Test
    void 年度分区落盘可列出并再次复算(@TempDir Path tmp) {
        SyntheticDataset ds = SyntheticDataset.load();
        ds.recomputeAll(tmp, 2026);

        List<String> landed = ds.listLandedQuarters(tmp, 2026);
        assertEquals(List.of("2026Q1", "2026Q2", "2026Q3", "2026Q4"), landed);

        List<RecomputeResult> reloaded = ds.reloadResults(tmp, 2026);
        assertEquals(39 * 4, reloaded.size(), "可按年分区读回全部复算结果");
        for (RecomputeResult r : reloaded) {
            SyntheticDataset.ExpectedValue ev = ds.oracle(r.getIndicatorCode(), r.getQuarter());
            assertEquals(ev.getNumerator(), r.getNumerator().intValue(),
                    "再次复算核对分子：" + r.getIndicatorCode() + "@" + r.getQuarter());
            assertEquals(ev.getDenominator(), r.getDenominator().intValue(),
                    "再次复算核对分母：" + r.getIndicatorCode() + "@" + r.getQuarter());
        }

        // 落盘只留聚合值 + 口径元数据，不含患者明细
        Path one = tmp.resolve("2026").resolve("2026Q1").resolve("MER-HR-01.json");
        String content = AtomicFiles.readString(one);
        assertTrue(content.contains("\"numerator\""), "落盘含聚合分子");
        assertTrue(content.contains("\"cache_key\""), "落盘含口径元数据");
        assertFalse(content.contains("encounter_id"), "患者可识别明细不落盘");
    }

    @Test
    void 差异能在运行摘要定位到具体季度与指标(@TempDir Path tmp) {
        SyntheticDataset ds = SyntheticDataset.load();
        Map<String, SyntheticDataset.ExpectedValue> override = new HashMap<>();
        override.put("MER-TL-01|2026Q1",
                new SyntheticDataset.ExpectedValue(999, 300, "人为注入 oracle 不一致"));

        RunSummary summary = ds.recomputeAll(tmp, 2026, override);
        assertFalse(summary.getFailedSteps().isEmpty());
        boolean located = summary.getFailedSteps().stream().anyMatch(f ->
                f.getIndicatorCode().equals("MER-TL-01")
                        && f.getQuarter().equals("2026Q1")
                        && f.getReason().contains("999"));
        assertTrue(located, "差异须能在运行摘要定位到具体季度与具体指标");
    }
}
