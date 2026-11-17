package com.infection.metric.pkg;

import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务47：口径包三清单分类（新增 / 实质口径变更 / 本院例外）。 */
class PackageClassifyTest {

    private static final ConditionTree NUM_A = ConditionTree.leaf("dept_code", "eq", "0301");
    private static final ConditionTree DEN_A_V1 = ConditionTree.leaf("discharge_flag", "eq", "1");
    private static final ConditionTree DEN_A_V2 = ConditionTree.leaf("discharge_flag", "eq", "2");
    private static final ConditionTree NUM_X = ConditionTree.leaf("site", "eq", "呼吸道");
    private static final ConditionTree DEN_X = ConditionTree.leaf("encounter_type", "eq", "住院");

    private static MetricDefinition local(String code, String version, String title,
                                          ConditionTree num, ConditionTree den) {
        return MetricDefinition.builder(code, version).title(title)
                .numerator(num).denominator(den)
                .status(MetricStatus.PUBLISHED).build();
    }

    private static PackageEntry upper(String no, String code, String name,
                                      ConditionTree num, ConditionTree den) {
        return PackageEntry.builder()
                .entryNo(no).indicatorCode(code).indicatorName(name)
                .definition("上级口径定义 " + code)
                .numeratorTree(num).denominatorTree(den)
                .numerator(num.toJson().toString()).denominator(den.toJson().toString())
                .deptCodes(List.of("0301"))
                .effectiveFrom("2026-01-01").effectiveTo("2026-12-31")
                .build();
    }

    private static Fixture fixture() {
        List<MetricDefinition> lib = List.of(
                local("HAI-001", "v1", "同期出院患者感染率", NUM_A, DEN_A_V1),
                local("HAI-003", "v1", "同名多版本指标", NUM_X, DEN_X),
                local("HAI-003", "v2", "同名多版本指标", NUM_X, DEN_X), // 同名多版本 → 无法唯一对齐
                local("HAI-LOCAL-9", "v1", "本院自有例外", NUM_X, DEN_X)  // 上级无 → 本院例外
        );
        List<PackageEntry> upper = List.of(
                upper("E01", "HAI-002", "新增手术感染率", NUM_X, DEN_X),          // 新增
                upper("E02", "HAI-001", "同期出院患者感染率", NUM_A, DEN_A_V2),    // 实质变更（分母不同）
                upper("E03", "HAI-003", "同名多版本指标", NUM_X, DEN_X)            // 待人工判定
        );
        ParsedPackage pkg = new ParsedPackage("PKG-2026Q1", "json", "region", upper);
        ThreeLists lists = new PackageClassifier(lib).classify(pkg);
        return new Fixture(lib, lists);
    }

    private record Fixture(List<MetricDefinition> lib, ThreeLists lists) {}

    @Test
    void 新增判定_上级有本院无() {
        ThreeLists lists = fixture().lists();

        assertEquals(1, lists.getNewItems().size(), "HAI-002 上级有、本院无，应归为新增");
        ClassifiedEntry e = lists.getNewItems().get(0);
        assertEquals(ClassifiedEntry.Category.NEW, e.getCategory());
        assertEquals("HAI-002", e.getIndicatorCode());
        assertTrue(e.getBasisSource().contains("无对应编码"), "每条须带依据来源");
    }

    @Test
    void 实质口径变更判定_指纹不同且列条件级差异() {
        ThreeLists lists = fixture().lists();

        assertEquals(1, lists.getChangedItems().size(), "HAI-001 分母变化应归为实质口径变更");
        ClassifiedEntry e = lists.getChangedItems().get(0);
        assertEquals(ClassifiedEntry.Category.CHANGED, e.getCategory());
        assertEquals("HAI-001", e.getIndicatorCode());
        assertTrue(e.getBasisSource().contains("canonical form 指纹不同"), "依据来源须说明指纹判定");
        assertFalse(e.getConditionDiffs().isEmpty(), "须列出条件级差异");
        assertTrue(e.getConditionDiffs().stream().anyMatch(d -> d.contains("上级新增条件") || d.contains("本院移除条件")),
                "条件级差异须逐片叶子给出增删");
    }

    @Test
    void 本院例外判定_上级无本院自有且口径不失效() {
        Fixture f = fixture();

        assertEquals(1, f.lists().getLocalExceptions().size());
        ClassifiedEntry e = f.lists().getLocalExceptions().get(0);
        assertEquals(ClassifiedEntry.Category.LOCAL_EXCEPTION, e.getCategory());
        assertTrue(e.getIndicatorCode().equals("HAI-LOCAL-9"));
        assertTrue(e.getNote().contains("不因导入失效"), "本院例外须说明口径不因导入失效");
        // 本院口径状态仍是 PUBLISHED，未被导入动作改动
        MetricDefinition localOwned = f.lib().stream()
                .filter(d -> d.getIndicatorCode().equals("HAI-LOCAL-9")).findFirst().orElseThrow();
        assertEquals(MetricStatus.PUBLISHED, localOwned.getStatus(), "本院自有口径不因导入而失效");
    }

    @Test
    void 无法唯一对齐标待人工判定不自动归类() {
        ThreeLists lists = fixture().lists();

        assertEquals(1, lists.getManualReview().size(), "同名多版本无法唯一对齐");
        ClassifiedEntry e = lists.getManualReview().get(0);
        assertEquals(ClassifiedEntry.Category.MANUAL_REVIEW, e.getCategory());
        assertEquals("HAI-003", e.getIndicatorCode());
        assertTrue(e.getBasisSource().contains("无法唯一对齐"));
        assertTrue(e.getNote().contains("人工确认"));
    }

    @Test
    void 三清单导出JSON与CSV每条带依据来源() {
        ThreeLists lists = fixture().lists();

        String json = lists.exportJson();
        assertTrue(json.contains("\"新增\"") && json.contains("\"实质口径变更\"") && json.contains("\"本院例外\""),
                "JSON 须含三清单分组");
        assertTrue(json.contains("basis_source"), "JSON 每条须带依据来源字段");

        String csv = lists.exportCsv();
        String[] lines = csv.trim().split("\\R");
        assertEquals(lists.total() + 1, lines.length, "CSV 行数 = 表头 + 全部条目");
        assertTrue(lines[0].contains("依据来源"), "CSV 表头须含依据来源列");
        assertTrue(csv.contains("无对应编码") && csv.contains("指纹不同"),
                "CSV 每行须带具体依据来源文本");
        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].contains("上级口径包") || lines[i].contains("本院已发布口径")
                            || lines[i].contains("条目 E") || lines[i].contains("本院口径"),
                    "第 " + i + " 行依据来源列不得为空");
        }
    }
}
