package com.infection.metric.pkg;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 任务12：格式 × 版本分派解析器。 */
class PackageParserTest {

    private static final String CSV = """
            条目编号,指标编码,指标名称,口径定义,分子,分母,适用科室,生效期,生效结束
            E01,HAI-001,同期出院患者感染率,住院期间发生感染的出院患者占比,感染部位=呼吸道,同期出院患者病历总数,0301;0302,2026-01-01,2026-12-31
            E02,HAI-002,手术部位感染率,手术后30天内切口感染占比,切口等级=Ⅲ,同期住院手术患者病历总数,0303,2026-01-01,2026-12-31
            E03,HAI-003,不完整指标,缺分子,,同期出院患者病历总数,0301,2026-01-01,
            """;

    private static final String JSON = """
            {
              "package_version": "PKG-2026Q1",
              "entries": [
                {
                  "entry_no": "E01", "indicator_code": "HAI-001", "indicator_name": "同期出院患者感染率",
                  "definition": "住院期间发生感染的出院患者占比",
                  "numerator": {"field": "dept_code", "cmp": "eq", "value": "0301"},
                  "denominator": {"op": "AND", "children": [
                    {"field": "discharge_date", "cmp": "between", "value": ["2026-01-01", "2026-03-31"]}
                  ]},
                  "dept_codes": ["0301"], "effective_from": "2026-01-01", "effective_to": "2026-03-31"
                },
                {
                  "entry_no": "E02", "indicator_code": "HAI-002", "indicator_name": "手术部位感染率",
                  "definition": "手术患者切口感染占比",
                  "numerator": "切口等级=Ⅲ", "denominator": "同期住院手术患者病历总数",
                  "dept_codes": ["0303"], "effective_from": "2026-01-01"
                }
              ]
            }
            """;

    private final PackageParser parser = new PackageParser();

    @Test
    void csv解析含表头且字段正确() {
        ParsedPackage p = parser.parseText(CSV, "pkg-2026.csv");

        assertEquals("csv", p.getFormat(), "扩展名 csv 应分派到 CSV 解析器");
        assertEquals(3, p.totalCount(), "表头之外应有 3 条数据行");
        assertEquals("HAI-001", p.getEntries().get(0).getIndicatorCode());
        assertEquals(2, p.getEntries().get(0).getDeptCodes().size(), "适用科室按分号拆分");
        assertTrue(p.getEntries().get(0).getDeptCodes().contains("0302"));
        assertTrue(p.getEntries().get(0).isComplete(), "完整条目不应被标记为不完整");
    }

    @Test
    void json解析支持条件树与包版本读取() {
        ParsedPackage p = parser.parseText(JSON, "region-package.json");

        assertEquals("json", p.getFormat(), "扩展名 json 应分派到 JSON 解析器");
        assertEquals("PKG-2026Q1", p.getPackageVersion(), "包版本应从内容读取，单次只解析一个版本");
        assertEquals(2, p.totalCount());
        assertNotNull(p.getEntries().get(0).getNumeratorTree(), "对象形式的分子应解析为条件树");
        assertNotNull(p.getEntries().get(0).getDenominatorTree());
        assertFalse(p.getEntries().get(0).fingerprint().isBlank(), "带条件树条目应能算出口径指纹");
        assertTrue(p.getEntries().get(1).isComplete(), "文本分子/分母 + 生效期齐备即完整");
    }

    @Test
    void xlsx不支持格式报E_PKG_001且含支持格式与转换提示() {
        AppException ex = assertThrows(AppException.class,
                () -> parser.parseText("任意内容,即便带逗号", "package.xlsx"));

        assertEquals(ErrorCodes.E_PKG_001, ex.getCode(), "不支持格式必须用 E_PKG_001");
        String rendered = ex.render();
        assertTrue(rendered.contains("不支持的格式"), "文案须明确说不支持的格式");
        assertTrue(rendered.contains("csv") && rendered.contains("json"), "须列出当前支持格式");
        assertTrue(rendered.contains("Excel") && rendered.contains("CSV UTF-8"),
                "须给出可执行的转换提示（Excel 另存为 CSV UTF-8）");
        assertTrue(ex.getHint().contains("Excel"), "hint 也须带转换提示");
    }

    @Test
    void 缺必需字段标为不完整并计数且不静默丢弃() {
        ParsedPackage p = parser.parseText(CSV, "pkg.csv");

        assertEquals(1, p.incompleteCount(), "E03 缺分子应计为不完整");
        assertEquals(2, p.completeCount());
        assertEquals(3, p.totalCount(), "不完整条目仍保留在结果中，不静默丢弃");
        PackageEntry bad = p.incompleteEntries().get(0);
        assertEquals("E03", bad.getEntryNo());
        assertTrue(bad.missingFields().contains("分子"), "缺失项应指明是分子");
        assertFalse(bad.isComplete());
        assertNotNull(p.findByEntryNo("E03"), "不完整条目应可反查");
    }

    @Test
    void 中间结构可序列化往返() {
        ParsedPackage p = parser.parseText(CSV, "pkg.csv");
        ParsedPackage back = ParsedPackage.fromJson(p.toJson());

        assertEquals(p.totalCount(), back.totalCount());
        assertEquals(p.incompleteCount(), back.incompleteCount(), "不完整计数须随往返保持");
        assertEquals(p.getPackageVersion(), back.getPackageVersion());
        assertEquals("HAI-001", back.getEntries().get(0).getIndicatorCode());
        assertEquals(2, back.getEntries().get(0).getDeptCodes().size());
    }

    @Test
    void 无扩展名时按内容头分派() {
        ParsedPackage byCsv = parser.parseText(CSV, "stdin");
        ParsedPackage byJson = parser.parseText(JSON, "stdin");

        assertEquals("csv", byCsv.getFormat(), "含逗号表头的内容头应嗅探为 csv");
        assertEquals("json", byJson.getFormat(), "{ 起头的内容头应嗅探为 json");
        assertEquals(3, byCsv.totalCount());
        assertEquals(2, byJson.totalCount());
    }
}
