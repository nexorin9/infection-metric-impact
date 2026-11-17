package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.AtomicFiles;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 任务37：可复现性（同输入两次结果相等）、摘要字段齐全、失败项在摘要中可见、产物可定位。 */
class RunSummaryTest {

    @Test
    void 同输入两次复算结果逐项相等(@TempDir Path tmp) {
        SyntheticDataset ds = SyntheticDataset.load();
        Path a = tmp.resolve("run-a");
        Path b = tmp.resolve("run-b");

        RunSummary s1 = ds.recomputeAll(a, 2026);
        RunSummary s2 = ds.recomputeAll(b, 2026);

        assertEquals(s1.reproducibilityKey(), s2.reproducibilityKey(),
                "口径版本 + 数据源版本 + 期间相同 → 输入指纹相同");
        assertEquals(s1.runId(), s2.runId(), "运行标识由输入派生，不含时间戳");
        assertEquals(s1.getResults().size(), s2.getResults().size());
        for (int i = 0; i < s1.getResults().size(); i++) {
            RecomputeResult x = s1.getResults().get(i);
            RecomputeResult y = s2.getResults().get(i);
            assertEquals(x.getIndicatorCode(), y.getIndicatorCode());
            assertEquals(x.getDefinitionVersion(), y.getDefinitionVersion());
            assertEquals(x.getQuarter(), y.getQuarter());
            assertEquals(x.getNumerator(), y.getNumerator());
            assertEquals(x.getDenominator(), y.getDenominator());
            assertEquals(x.getStatus(), y.getStatus());
        }
    }

    @Test
    void 摘要字段齐全且可由摘要定位全部产物(@TempDir Path tmp) {
        SyntheticDataset ds = SyntheticDataset.load();
        RunSummary s = ds.recomputeAll(tmp, 2026);
        Path runDir = tmp.resolve("runs").resolve("2026").resolve(s.runId());
        Path summaryFile = runDir.resolve("summary.json");
        assertTrue(AtomicFiles.exists(summaryFile), "摘要落盘 data/runs/<年度>/<运行标识>/summary.json");

        JsonNode n = JsonSupport.read(summaryFile);
        assertTrue(n.has("run_id"));
        assertTrue(n.path("reproducibility").has("definition_versions"));
        assertTrue(n.path("reproducibility").has("data_source_version"));
        assertTrue(n.path("reproducibility").has("periods"));
        assertTrue(n.path("reproducibility").has("input_fingerprint"));
        assertEquals("synthetic-20261109",
                n.path("reproducibility").path("data_source_version").asText());

        assertEquals(156, n.path("metrics").size(), "各指标分子 / 分母 / 状态齐全");
        JsonNode m0 = n.path("metrics").get(0);
        assertTrue(m0.has("numerator"));
        assertTrue(m0.has("denominator"));
        assertTrue(m0.has("status"));
        assertTrue(n.has("failed_steps"));
        assertTrue(n.has("unpublished_definitions"));
        assertEquals(4, n.path("landed_quarters").size(), "落库季度齐全");
        assertTrue(n.has("fetch_millis"));
        assertTrue(n.has("compute_millis"));
        assertTrue(n.has("results_dir"));
        assertEquals(156, n.path("artifacts").size(), "摘要列出全部产物");

        // 摘要与结果目录对应关系：由 artifacts 相对路径定位该次运行的全部产物
        RunSummary read = RunSummary.fromJson(n);
        assertEquals(156, read.getArtifacts().size());
        for (Path p : read.artifactPaths(runDir)) {
            assertTrue(AtomicFiles.exists(p), "产物可读：" + p);
            assertTrue(JsonSupport.read(p).has("numerator"));
        }
        assertTrue(runDir.endsWith(Path.of("runs", "2026", s.runId())));
    }

    @Test
    void 失败项与未发布口径在摘要中可见且不按0计入分母(@TempDir Path tmp) {
        ConditionTree tree = ConditionTree.leaf("quarter", "eq", "2026Q1");
        MetricDefinition draft = MetricDefinition.builder("M-UNPUB", "v1")
                .numerator(tree).denominator(tree)
                .status(MetricStatus.DRAFT).build();
        MetricDefinition published = MetricDefinition.builder("M-FAIL", "v1")
                .numerator(tree).denominator(tree)
                .status(MetricStatus.PUBLISHED).build();

        RecordSupplier failing = q -> {
            throw new IllegalStateException("数据源不可达");
        };
        RecomputeEngine engine = new RecomputeEngine(failing, "ds-v1");

        assertThrows(AppException.class, () -> engine.recompute(draft, "2026Q1"));
        RecomputeResult failed = engine.recompute(published, "2026Q1");
        assertTrue(failed.isFailed());
        assertNull(failed.getDenominator(), "失败不得按 0 计入分母");

        RunSummary s = engine.getSummary();
        assertTrue(s.getUnpublishedDefinitions().contains("M-UNPUB|v1"), "未发布口径清单可见");
        assertFalse(s.getFailedSteps().isEmpty(), "失败步骤可见");
        assertTrue(s.getFailedSteps().get(0).getReason().contains("数据源不可达"), "失败原因可读");
        assertTrue(s.getResults().stream().anyMatch(r -> r.isFailed() && r.getDenominator() == null));

        Path runDir = s.write(tmp, 2026);
        JsonNode n = JsonSupport.read(runDir.resolve("summary.json"));
        assertTrue(n.path("failed_steps").size() >= 1, "落盘后失败项仍可见");
        assertTrue(n.toString().contains("M-UNPUB|v1"));
        assertTrue(runDir.endsWith(Path.of("runs", "2026", s.runId())));
    }
}
