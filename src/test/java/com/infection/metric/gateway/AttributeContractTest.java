package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.recompute.Attribution;
import com.infection.metric.recompute.BusinessDimension;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务45：两版差异业务归因契约，本地 schema 校验与规则通道合并（规则优先）。
 */
class AttributeContractTest {

    private static ObjectNode item(String dimension, String evidenceRef, String... periods) {
        ObjectNode o = JsonSupport.object();
        o.put("dimension", dimension);
        o.put("detail", "模型建议：分子范围可能收紧");
        o.put("evidence_ref", evidenceRef);
        ArrayNode arr = o.putArray("affected_periods");
        for (String p : periods) {
            arr.add(p);
        }
        o.put("confidence", 0.74);
        return o;
    }

    private static AppException expectSchemaFail(JsonNode response) {
        return assertThrows(AppException.class,
                () -> new AttributeContract().validateResponse(response, Fixtures.attributePayload()));
    }

    @Test
    void 合规归因响应通过校验_中文维度也合法() {
        ArrayNode resp = Fixtures.attributeResponse();
        assertDoesNotThrow(() -> new AttributeContract().validateResponse(resp, Fixtures.attributePayload()));
        assertEquals("attribute_diff", new AttributeContract().task());
        assertEquals("1.0", new AttributeContract().schemaVersion());

        ArrayNode cn = JsonSupport.array();
        cn.add(item("分子范围", "d1", "2026Q1"));
        assertDoesNotThrow(() -> new AttributeContract().validateResponse(cn, Fixtures.attributePayload()),
                "dimension 允许约定枚举的中文名");
    }

    @Test
    void 非法维度空evidence空periods及指向不存在条目判E_LLM_004() {
        ArrayNode badDim = JsonSupport.array();
        badDim.add(item("NOT_A_DIMENSION", "d1", "2026Q1"));
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(badDim).getCode(), "非法维度");

        ArrayNode emptyRef = JsonSupport.array();
        emptyRef.add(item("NUMERATOR_SCOPE", "   ", "2026Q1"));
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(emptyRef).getCode(), "空 evidence_ref");

        ArrayNode emptyPeriods = JsonSupport.array();
        emptyPeriods.add(item("NUMERATOR_SCOPE", "d1"));
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(emptyPeriods).getCode(), "空 affected_periods");

        ArrayNode ghostRef = JsonSupport.array();
        ghostRef.add(item("NUMERATOR_SCOPE", "ghost-entry", "2026Q1"));
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(ghostRef).getCode(),
                "evidence_ref 未指向具体差异条目");
    }

    @Test
    void 与规则通道合并_规则优先_模型结论待人工确认并带置信度与依据() {
        List<Attribution> rules = List.of(
                Attribution.rule(BusinessDimension.NUMERATOR_SCOPE,
                        "规则：分子树变化", "rule:NUMERATOR_SCOPE"));
        ArrayNode model = Fixtures.attributeResponse();

        List<Attribution> merged = AttributeContract.merge(rules, model);
        assertEquals(2, merged.size());
        assertEquals(Attribution.Source.RULE, merged.get(0).getSource(), "规则结论优先，排在前面");
        assertEquals(1.0, merged.get(0).getConfidence(), 0.0001);
        assertFalse(merged.get(0).isPendingHumanConfirmation());

        Attribution candidate = merged.get(1);
        assertEquals(Attribution.Source.MODEL, candidate.getSource());
        assertTrue(candidate.isPendingHumanConfirmation(), "模型结论标「待人工确认」");
        assertEquals(0.74, candidate.getConfidence(), 0.0001, "带置信度");
        assertEquals("d1", candidate.getEvidenceRef(), "带依据引用");
        assertTrue(candidate.renderCn().contains("待人工确认"));
    }

    @Test
    void 替身返回非法维度_校验失败且不重试() {
        FakeTransport fake = new FakeTransport();
        ArrayNode resp = JsonSupport.array();
        resp.add(item("ILLEGAL_DIM", "d1", "2026Q1"));
        fake.body = JsonSupport.compact(resp);
        ModelGateway gw = Fixtures.gateway(fake);

        AppException ex = assertThrows(AppException.class,
                () -> gw.call(AttributeContract.TASK, "1.0", Fixtures.attributePayload()));
        assertEquals(ErrorCodes.E_LLM_004, ex.getCode(), "非法 dimension → schema 校验失败");
        assertEquals(1, fake.calls, "schema 校验失败不重试");
    }
}
