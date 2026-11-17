package com.infection.metric.metriclib;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 任务19：官方 27 项基准集与四个复用分母的一致性。 */
class BenchmarkSetTest {

    @Test
    void 二十七项编号齐全且confidence全为official() {
        BenchmarkSet bs = BenchmarkSet.load();

        assertEquals(27, bs.size());
        assertEquals(27, bs.codes().size());
        assertEquals(27, bs.indicatorNos().size());
        assertTrue(bs.indicatorNos().contains("指标一"));
        assertTrue(bs.indicatorNos().contains("指标二十七"));
        assertDoesNotThrow(bs::assertOfficial);

        for (String code : bs.codes()) {
            JsonNode n = bs.byCode(code);
            assertNotNull(n, "编码 " + code + " 应能反查到指标");
            assertEquals("official", n.path("confidence").asText());
        }
    }

    @Test
    void 四个复用分母各自归属指标数与官方一致() {
        BenchmarkSet bs = BenchmarkSet.load();
        Map<String, Integer> declared = bs.declaredGroupCounts();

        assertEquals(4, declared.size());
        assertEquals(8, declared.get("同期出院患者病历总数"));
        assertEquals(2, declared.get("同期住院患者病历总数"));
        assertEquals(2, declared.get("同期住院手术患者病历总数"));
        assertEquals(2, declared.get("同期出院手术患者病历总数"));

        assertEquals(8, bs.denominatorGroup("同期出院患者病历总数"));
        assertEquals(2, bs.denominatorGroup("同期住院患者病历总数"));
        assertEquals(2, bs.denominatorGroup("同期住院手术患者病历总数"));
        assertEquals(2, bs.denominatorGroup("同期出院手术患者病历总数"));
        assertEquals(0, bs.denominatorGroup("不存在的分母"));
        assertDoesNotThrow(bs::assertOfficial);
    }

    @Test
    void 同期住院手术与同期出院手术是两个不同口径() {
        BenchmarkSet bs = BenchmarkSet.load();
        List<String> inpatient = bs.denominatorGroupCodes("同期住院手术患者病历总数");
        List<String> discharged = bs.denominatorGroupCodes("同期出院手术患者病历总数");

        assertEquals(2, inpatient.size());
        assertEquals(2, discharged.size());
        assertEquals(Set.of("MER-D&T-04", "MER-TL-02"), new HashSet<>(inpatient));
        assertEquals(Set.of("MER-TQ-05", "MER-TQ-06"), new HashSet<>(discharged));
        assertNotEquals(inpatient, discharged, "两个仅差一词的分母不得混为同一口径");
        assertTrue(Collections.disjoint(inpatient, discharged), "两个口径的归属指标不得重叠");
        assertTrue(bs.declaredGroupCounts().containsKey("同期住院手术患者病历总数"));
        assertTrue(bs.declaredGroupCounts().containsKey("同期出院手术患者病历总数"));
    }

    @Test
    void 按编码与编号反查到真实官方条目() {
        BenchmarkSet bs = BenchmarkSet.load();

        JsonNode byCode = bs.byCode("MER-TL-03");
        assertNotNull(byCode);
        assertEquals("出院记录24小时内完成率", byCode.path("title").asText());
        assertEquals("同期出院患者病历总数", byCode.path("denominator_key").asText());
        assertEquals("official", byCode.path("confidence").asText());

        JsonNode byNo = bs.byIndicatorNo("指标四");
        assertNotNull(byNo);
        assertEquals("MER-TL-01", byNo.path("indicator_code").asText());
        assertNull(bs.byCode("NOT-EXIST"));
        assertNull(bs.byIndicatorNo("指标九十九"));
    }
}
