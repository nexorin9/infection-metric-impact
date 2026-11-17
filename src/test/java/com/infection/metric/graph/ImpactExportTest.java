package com.infection.metric.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务25：JSON/CSV 两套导出、中文列名、人话说明、交集与空影响面。 */
class ImpactExportTest {

    private static ImpactList sample() {
        ImpactList.ChangeEntry change = new ImpactList.ChangeEntry(
                "MER-TL-03", "2026.1", "出院记录24小时内完成率",
                "分子新增排除条件：切口等级不等于 Ⅲ");
        List<ImpactList.AffectedIndicator> affected = List.of(
                new ImpactList.AffectedIndicator("MER-TL-04", "病案首页24小时内完成率", "2026.1", 1,
                        "复用同一分母口径，需随变更一并复核", "复用「同期出院患者病历总数」分母"),
                new ImpactList.AffectedIndicator("MER-TQ-01", "出院患者病历2日归档率", "2026.1", 1,
                        "复用同一分母口径，需随变更一并复核", "复用「同期出院患者病历总数」分母"),
                new ImpactList.AffectedIndicator("MER-TQ-09", "甲级病历率", "2026.1", 1,
                        "复用同一分母口径，需随变更一并复核", "复用「同期出院患者病历总数」分母"));
        return new ImpactList(change, List.of("2026Q1", "2026Q2"), affected,
                "《病案管理质量控制指标（2021年版）》",
                "共 3 项指标复用同一分母，需一并复核；2026Q1，2026Q2");
    }

    private static long dataRows(String csv) {
        return csv.lines()
                .filter(l -> !l.isBlank())
                .filter(l -> !l.equals(ImpactExporter.CSV_HEADER))
                .count();
    }

    @Test
    void CSV列名用中文业务名() {
        assertEquals("受影响指标编码,指标名称,需重算季度,说明,来源", ImpactExporter.CSV_HEADER);
        assertTrue(ImpactExporter.toCsv(sample()).startsWith(ImpactExporter.CSV_HEADER));
    }

    @Test
    void JSON与CSV内容一致且CSV行数等于受影响指标数() {
        ImpactList list = sample();
        String csv = ImpactExporter.toCsv(list);

        assertEquals(list.getAffected().size(), (int) dataRows(csv), "CSV 数据行数 = 受影响指标数");
        assertEquals(3, ImpactExporter.csvDataRowCount(list));

        JsonNode json = ImpactExporter.toJsonNode(list);
        JsonNode arr = json.path("affected_indicators");
        assertEquals(3, arr.size());
        for (JsonNode a : arr) {
            String code = a.path("indicator_code").asText();
            String name = a.path("name").asText();
            assertTrue(csv.contains(code), "CSV 应含编码 " + code);
            assertTrue(csv.contains(name), "CSV 应含名称 " + name);
            assertTrue(csv.contains(a.path("explanation").asText()), "CSV 应含人话说明");
            assertTrue(csv.contains(a.path("source").asText()), "CSV 应含来源");
        }
        assertTrue(csv.contains("2026Q1") && csv.contains("2026Q2"), "需重算季度一致");
        assertEquals(arr.size(), (int) dataRows(csv), "JSON 数组长度与 CSV 行数一致");
    }

    @Test
    void 空影响面导出结构完整且说明明确() {
        ImpactList empty = new ImpactList(
                new ImpactList.ChangeEntry("X", "v1", "无下游口径", "无口径变更"),
                List.of(), List.of(),
                "《病案管理质量控制指标（2021年版）》",
                "该口径无下游依赖，无需重算；无历史季度需重算");

        JsonNode n = ImpactExporter.toJsonNode(empty);
        assertTrue(n.has("title") && n.has("change") && n.has("source") && n.has("note"));
        assertTrue(n.path("recompute_quarters").isArray());
        assertEquals(0, n.path("recompute_quarters").size());
        assertEquals("无历史季度需重算", n.path("recompute_quarters_text").asText());
        assertTrue(n.path("affected_indicators").isArray());
        assertEquals(0, n.path("affected_indicators").size());
        assertEquals(0, n.path("affected_count").asInt());
        assertTrue(n.path("note").asText().contains("无需重算"));
        assertTrue(n.path("note").asText().contains("无历史季度需重算"));

        String csv = ImpactExporter.toCsv(empty);
        assertTrue(csv.startsWith(ImpactExporter.CSV_HEADER), "空影响面 CSV 表头完整");
        assertEquals(0, dataRows(csv), "空影响面无数据行");
    }

    @Test
    void 人话说明把条件级差异翻成业务句不堆黑话() {
        String h = ImpactExporter.humanize("分子", "新增排除条件", "incision_grade", "ne", "Ⅲ");
        assertEquals("分子新增排除条件：切口等级不等于 Ⅲ", h);
        assertFalse(h.contains("incision_grade"));
        assertFalse(h.contains(" ne "));

        ImpactExporter.ConditionDiff d = new ImpactExporter.ConditionDiff(
                "分母", "修改取值", "discharge_date", "between", "2026-01-01 至 2026-03-31");
        assertTrue(d.humanize().contains("出院日期"));
        assertTrue(d.humanize().contains("介于"));
        assertFalse(d.humanize().contains("discharge_date"));
        assertEquals("分母修改取值：出院日期介于 2026-01-01 至 2026-03-31", d.humanize());
    }

    @Test
    void 需重算季度无交集写无历史季度需重算() {
        ImpactList list = new ImpactList(
                new ImpactList.ChangeEntry("A", "v1", "甲", "口径变更"),
                List.of(), // 交集为空
                List.of(new ImpactList.AffectedIndicator("B", "乙", "v1", 1,
                        "复用同一分母口径", "复用分母")),
                "官方文件", "无需重算历史季度");
        assertEquals("无历史季度需重算", list.recomputeQuartersText());
        assertEquals("无历史季度需重算", list.toJson().path("recompute_quarters_text").asText());
        assertTrue(ImpactExporter.toCsv(list).contains("无历史季度需重算"),
                "CSV 的需重算季度列不留空");
    }

    @Test
    void JSON与CSV落盘可读且可回读(@TempDir Path temp) throws Exception {
        ImpactList list = sample();
        Path jp = temp.resolve("impact.json");
        Path cp = temp.resolve("impact.csv");
        ImpactExporter.exportJson(list, jp);
        ImpactExporter.exportCsv(list, cp);

        assertTrue(Files.exists(jp) && Files.exists(cp));
        JsonNode n = JsonSupport.read(jp);
        assertEquals(3, n.path("affected_indicators").size());

        String csv = Files.readString(cp);
        assertTrue(csv.startsWith(ImpactExporter.CSV_HEADER));
        assertTrue(csv.contains("MER-TL-04"));

        ImpactList back = ImpactList.fromJson(n);
        assertEquals(3, back.getAffected().size());
        assertEquals(list.getChange().getCode(), back.getChange().getCode());
        assertEquals(list.getRecomputeQuarters(), back.getRecomputeQuarters());
    }
}
