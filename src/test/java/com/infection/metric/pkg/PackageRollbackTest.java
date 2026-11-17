package com.infection.metric.pkg;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务50：合并中途失败的整体回滚（用 @TempDir 落盘模拟库，不污染项目 data/）。 */
class PackageRollbackTest {

    @TempDir
    Path temp;

    private static final String PV = "PKG-2026Q1";
    private static final String TV = "v2";

    private static List<MetricDefinition> existing() {
        return List.of(
                MetricDefinition.builder("HAI-001", "v1").title("指标一")
                        .numerator(ConditionTree.leaf("dept_code", "eq", "0301"))
                        .denominator(ConditionTree.leaf("discharge_flag", "eq", "1"))
                        .status(MetricStatus.PUBLISHED).effectiveFrom("2026-01-01").build(),
                MetricDefinition.builder("HAI-002", "v1").title("指标二")
                        .numerator(ConditionTree.leaf("dept_code", "eq", "0302"))
                        .denominator(ConditionTree.leaf("discharge_flag", "eq", "1"))
                        .status(MetricStatus.PUBLISHED).effectiveFrom("2026-01-01").build()
        );
    }

    private static MergeTransaction.MergeItem item(String code, String name) {
        return new MergeTransaction.MergeItem(code, name,
                ConditionTree.leaf("dept_code", "eq", "0301"),
                ConditionTree.leaf("discharge_flag", "eq", "1"),
                List.of("0301"), "2026-01-01", "2026-12-31");
    }

    private static List<MergeTransaction.MergeItem> items() {
        return List.of(item("HAI-003", "新增甲"), item("HAI-004", "新增乙"), item("HAI-005", "新增丙"));
    }

    private MergeTransaction seededTx() {
        MergeTransaction tx = new MergeTransaction(temp, PV, TV);
        tx.seed(existing());
        return tx;
    }

    @Test
    void 注入中途失败后库状态与合并前逐项一致() {
        MergeTransaction tx = seededTx();
        String beforeDefs = tx.readDefinitionsRaw();
        String beforeIndex = tx.readIndexRaw();
        String beforeState = tx.readStateRaw();
        String baseline = tx.baselineSignature();

        AppException ex = assertThrows(AppException.class, () -> tx.merge(items(), 2));

        assertNotNull(ex);
        assertEquals(beforeDefs, tx.readDefinitionsRaw(), "口径条目文件须与合并前逐项一致");
        assertEquals(beforeIndex, tx.readIndexRaw(), "索引须还原到合并前状态");
        assertEquals(beforeState, tx.readStateRaw(), "口径包状态文件须与合并前一致");
        assertEquals(PackageStatus.REVIEWED, tx.currentStatus(), "口径包状态回退为 REVIEWED");
        assertEquals(baseline, tx.baselineSignature(), "回滚后版本集合回到合并前基线");
        assertFalse(tx.isCommitted(), "失败后不得标记为已提交");
    }

    @Test
    void 回滚有审计记录与运行摘要含失败步骤回滚条目数回滚后版本() {
        MergeTransaction tx = seededTx();
        String baseline = tx.baselineSignature();

        assertThrows(AppException.class, () -> tx.merge(items(), 2));

        RollbackReport report = tx.getRollbackReport();
        assertNotNull(report, "须生成回滚报告");
        assertTrue(report.getFailedStep().contains("STEP-3"), "报告含失败步骤");
        assertEquals(2, report.getRolledBackEntryCount(), "报告含回滚条目数（前两步已写入被撤销）");
        assertEquals(baseline, report.getVersionAfterRollback(), "报告含回滚后版本");

        String audit = tx.readAuditRaw();
        assertTrue(audit.contains("回滚审计") && audit.contains("STEP-3"),
                "审计记录本次回滚");
        assertTrue(audit.contains("回滚条目数=2") && audit.contains("回滚后版本"),
                "审计含回滚条目数与回滚后版本");

        String summary = tx.readSummaryRaw();
        assertTrue(summary.contains("ROLLED_BACK") && summary.contains("failed_step"),
                "运行摘要记录本次回滚");
        assertTrue(summary.contains("rolled_back_entry_count") && summary.contains("version_after_rollback"),
                "运行摘要含回滚条目数与回滚后版本");
    }

    @Test
    void 合并步骤逐条记录进度且失败步骤不计入已写入() {
        MergeTransaction tx = seededTx();

        assertThrows(AppException.class, () -> tx.merge(items(), 2));

        List<String> steps = tx.getSteps();
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("BEGIN")), "记录合并前快照");
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("STEP-1")), "第 1 步写入进度");
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("STEP-2")), "第 2 步写入进度");
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("ROLLBACK")), "记录回滚动作");
        assertFalse(steps.stream().anyMatch(s -> s.startsWith("STEP-3")), "失败步骤不得记为已写入进度");
    }

    @Test
    void 第一步即失败时回滚条目数为零且库仍一致() {
        MergeTransaction tx = seededTx();
        String beforeDefs = tx.readDefinitionsRaw();
        String beforeIndex = tx.readIndexRaw();

        assertThrows(AppException.class, () -> tx.merge(items(), 0));

        assertEquals(0, tx.getRollbackReport().getRolledBackEntryCount(), "尚未写入任何条目");
        assertEquals(beforeDefs, tx.readDefinitionsRaw());
        assertEquals(beforeIndex, tx.readIndexRaw());
        assertEquals(PackageStatus.REVIEWED, tx.currentStatus());
    }

    @Test
    void 无失败时合并成功且写入条目与索引齐全() {
        MergeTransaction tx = seededTx();

        tx.merge(items()); // 不注入失败

        assertTrue(tx.isCommitted(), "全部步骤成功应提交");
        assertEquals(PackageStatus.MERGED, tx.currentStatus());
        JsonNode defs = JsonSupport.parse(tx.readDefinitionsRaw()).path("definitions");
        assertEquals(5, defs.size(), "合并前 2 条 + 写入 3 条");
        JsonNode idx = JsonSupport.parse(tx.readIndexRaw()).path("entries");
        assertEquals(5, idx.size(), "索引同步新增 3 条");
        assertTrue(tx.readSummaryRaw().contains("MERGED"), "成功运行摘要可见");
        assertNull(tx.getRollbackReport(), "成功路径不应产生回滚报告");
    }
}
