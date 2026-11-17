package com.infection.metric.metriclib;

import com.infection.metric.common.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 任务21：案例集加载、跑批器失败明细、official 对齐基准集、draft 不冒充 official。 */
class CaseSetTest {

    @TempDir Path tempDir;

    /** 全部主断言用 @TempDir 自建案例文件，不依赖项目根 cases/ 路径。 */
    private void writeCases(Path dir, boolean corruptOfficial) throws IOException {
        String title = corruptOfficial ? "被改坏的错误标题" : "出院记录24小时内完成率";
        String official = """
                {
                  "cases": [
                    {"case_id":"case-tl-03","input":{"indicator_code":"MER-TL-03"},
                     "expect":{"title":"%s","missing_formula":true,"denominator_key":"同期出院患者病历总数"},
                     "basis":"官方 指标六","confidence":"official"},
                    {"case_id":"case-tl-01","input":{"indicator_code":"MER-TL-01"},
                     "expect":{"title":"入院记录24小时内完成率","denominator_text":"同期住院患者病历总数"},
                     "basis":"官方 指标四","confidence":"official"},
                    {"case_id":"case-hr-01","input":{"indicator_code":"MER-HR-01"},
                     "expect":{"title":"住院病案管理人员月均负担出院患者病历数","denominator_text":"实际工作总月数"},
                     "basis":"官方 指标一","confidence":"official"}
                  ],
                  "confidence":"official","note":"测试用官方基准案例"
                }
                """.formatted(title);
        Files.writeString(dir.resolve(CaseSet.OFFICIAL_FILE), official);

        String draft = """
                {
                  "cases": [
                    {"case_id":"draft-inc-01","input":{"indicator_code":"HAI-INC-01","quarter":"2026Q1"},
                     "expect":{"rule":"分子含 mdr_flag=true 才计入","scope":"脱敏样例"},
                     "basis":"本院院感口径草稿（脱敏），非官方口径","confidence":"draft"},
                    {"case_id":"draft-ssi-02","input":{"indicator_code":"HAI-SSI-02","wound_class":"III"},
                     "expect":{"rule":"切口等级 Ⅲ 单独统计","scope":"脱敏样例"},
                     "basis":"本院院感口径草稿（脱敏），非官方口径","confidence":"draft"}
                  ],
                  "confidence":"draft","note":"院感侧草稿案例，不冒充官方口径"
                }
                """;
        Files.writeString(dir.resolve(CaseSet.DRAFT_FILE), draft);
    }

    @Test
    void 案例集可加载且跑批全部通过() throws IOException {
        writeCases(tempDir, false);
        CaseSet set = CaseSet.fromDirectory(tempDir);

        assertEquals(3, set.officialCases().size());
        assertEquals(2, set.draftCases().size());
        assertEquals(5, set.size());
        assertEquals("case-tl-03", set.officialCases().get(0).getCaseId());

        CaseRunner.Result result = CaseRunner.run(set);
        assertEquals(0, result.failedCount());
        assertEquals(5, result.passedCount());
        assertTrue(result.failures().isEmpty());
        assertTrue(result.summary().contains("通过 5 个，失败 0 个"));
    }

    @Test
    void 人为改坏一个案例时跑批器报出该案例失败并指出期望与实际差异() throws IOException {
        writeCases(tempDir, true);
        CaseRunner.Result result = CaseRunner.run(CaseSet.fromDirectory(tempDir));

        assertEquals(1, result.failedCount());
        assertEquals(4, result.passedCount());
        assertEquals(1, result.failures().size());

        CaseRunner.Result.Failure f = result.failures().get(0);
        assertEquals("case-tl-03", f.getCaseId());
        assertEquals("title", f.getField());
        assertTrue(f.getExpected().contains("被改坏的错误标题"));
        assertTrue(f.getActual().contains("出院记录24小时内完成率"));
        assertTrue(result.summary().contains("期望"));
        assertTrue(result.summary().contains("实际"));
        assertTrue(result.summary().contains("case-tl-03"));
    }

    @Test
    void official案例的指标编号全部可对齐BenchmarkSet() throws IOException {
        writeCases(tempDir, false);
        CaseSet set = CaseSet.fromDirectory(tempDir);
        BenchmarkSet bs = BenchmarkSet.load();

        assertEquals(3, set.officialCases().size());
        for (CaseSet.Case c : set.officialCases()) {
            String code = c.getInput().path("indicator_code").asText();
            assertNotNull(bs.byCode(code), "官方案例指标编号 " + code + " 必须能在基准集中找到对应项");
            assertEquals("official", c.getConfidence());
        }
        assertEquals(0, CaseRunner.run(set).failedCount());
    }

    @Test
    void official案例指标编号不在基准集时跑批显式失败() throws IOException {
        Files.writeString(tempDir.resolve(CaseSet.OFFICIAL_FILE), """
                {"cases":[
                  {"case_id":"bad-code","input":{"indicator_code":"NOT-IN-BENCHMARK"},
                   "expect":{"title":"任意"},"basis":"测试","confidence":"official"}
                ],"confidence":"official","note":""}
                """);
        Files.writeString(tempDir.resolve(CaseSet.DRAFT_FILE),
                "{\"cases\":[],\"confidence\":\"draft\",\"note\":\"\"}");

        CaseRunner.Result result = CaseRunner.run(CaseSet.fromDirectory(tempDir));
        assertEquals(1, result.failedCount());
        assertEquals("indicator_code", result.failures().get(0).getField());
        assertTrue(result.failures().get(0).getActual().contains("无对应项"));
    }

    @Test
    void draft案例标draft不冒充official且标注被篡改时加载被拒() throws IOException {
        writeCases(tempDir, false);
        CaseSet set = CaseSet.fromDirectory(tempDir);

        assertEquals(2, set.draftCases().size());
        for (CaseSet.Case c : set.draftCases()) {
            assertEquals("draft", c.getConfidence());
            assertTrue(c.getBasis().contains("非官方口径"));
        }
        assertTrue(set.officialCases().stream().noneMatch(c -> "draft".equals(c.getConfidence())));

        Path draftFile = tempDir.resolve(CaseSet.DRAFT_FILE);
        String tampered = Files.readString(draftFile)
                .replace("\"confidence\":\"draft\"", "\"confidence\":\"official\"");
        Files.writeString(draftFile, tampered);
        AppException ex = assertThrows(AppException.class, () -> CaseSet.fromDirectory(tempDir));
        assertEquals("E_DATA_001", ex.getCode());
        assertTrue(ex.render().contains("draft"));
    }

    @Test
    void 案例结构缺字段或文件缺失时显式失败() throws IOException {
        Files.writeString(tempDir.resolve(CaseSet.OFFICIAL_FILE), """
                {"cases":[
                  {"case_id":"missing-expect","input":{"indicator_code":"MER-TL-03"},
                   "basis":"测试","confidence":"official"}
                ],"confidence":"official","note":""}
                """);
        Files.writeString(tempDir.resolve(CaseSet.DRAFT_FILE),
                "{\"cases\":[],\"confidence\":\"draft\",\"note\":\"\"}");
        assertEquals("E_DATA_001",
                assertThrows(AppException.class, () -> CaseSet.fromDirectory(tempDir)).getCode());

        // 恢复合法 official 案例后，单独验证 draft 文件缺失的显式失败
        writeCases(tempDir, false);
        Files.delete(tempDir.resolve(CaseSet.DRAFT_FILE));
        AppException ex = assertThrows(AppException.class, () -> CaseSet.fromDirectory(tempDir));
        assertEquals("E_DATA_001", ex.getCode());
        assertTrue(ex.render().contains(CaseSet.DRAFT_FILE));
    }
}
