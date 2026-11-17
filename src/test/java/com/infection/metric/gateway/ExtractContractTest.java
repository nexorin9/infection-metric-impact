package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务43：口径散文 → 结构化条件树契约，本地 schema 校验、草稿落库与人工改写。
 */
class ExtractContractTest {

    private static AppException expectSchemaFail(JsonNode response) {
        return assertThrows(AppException.class,
                () -> new ExtractContract().validateResponse(response, Fixtures.extractPayload()));
    }

    @Test
    void 合规抽取响应通过校验且引用含原文片段() {
        ObjectNode resp = Fixtures.extractResponse();
        assertDoesNotThrow(() -> new ExtractContract().validateResponse(resp, Fixtures.extractPayload()));
        assertTrue(resp.path("citations").isArray());
        assertFalse(resp.path("citations").isEmpty());
        assertFalse(resp.path("citations").get(0).path("quote").asText().isBlank(),
                "citations 每条含原文片段引用");
        assertEquals("extract_definition", new ExtractContract().task());
        assertEquals("1.0", new ExtractContract().schemaVersion());
    }

    @Test
    void 字段缺失类型不符引用为空一律判E_LLM_004() {
        ObjectNode missing = Fixtures.extractResponse();
        missing.remove("numerator");
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(missing).getCode(), "字段缺失");

        ObjectNode wrongType = Fixtures.extractResponse();
        wrongType.set("numerator", JsonSupport.object());
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(wrongType).getCode(), "类型不符");

        ObjectNode emptyCitations = Fixtures.extractResponse();
        emptyCitations.putArray("citations");
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(emptyCitations).getCode(), "引用为空");

        ObjectNode noQuote = Fixtures.extractResponse();
        noQuote.putArray("citations").addObject().put("field", "dept_code");
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(noQuote).getCode(), "引用缺原文片段");
    }

    @Test
    void 替身返回契约外结构_校验失败且不重试() {
        FakeTransport fake = new FakeTransport();
        fake.body = "{\"unexpected\":\"structure\"}";
        CallAudit audit = new CallAudit();
        ModelGateway gw = Fixtures.gateway(fake, audit);

        AppException ex = assertThrows(AppException.class,
                () -> gw.call(ExtractContract.TASK, "1.0", Fixtures.extractPayload()));
        assertEquals(ErrorCodes.E_LLM_004, ex.getCode(), "契约外结构 → schema 校验失败");
        assertEquals(1, fake.calls, "schema 校验失败不重试（同输入必然同结果）");
        assertFalse(audit.withErrorCode(ErrorCodes.E_LLM_004).isEmpty());
        assertEquals(0, audit.all().get(0).getRetryCount());
    }

    @Test
    void 抽取结果先入草稿_人工改写后来源人工并留改写记录_状态仍DRAFT() {
        ExtractContract.DraftStore store = new ExtractContract.DraftStore();
        ObjectNode extracted = Fixtures.extractResponse();
        ExtractContract.Draft draft = store.save("HAI-INC-01", extracted);

        assertEquals("模型", draft.getSource(), "抽取结果先入草稿，来源「模型」");
        assertEquals(MetricStatus.DRAFT, draft.getStatus(), "模型输出永远是草稿");
        assertTrue(draft.getRewrites().isEmpty());
        assertEquals(extracted, draft.getContent());

        ObjectNode rewritten = Fixtures.extractResponse();
        rewritten.putObject("scope").putArray("deptCodes").add("0301").add("0302");
        ExtractContract.Draft after = store.rewrite(draft.getId(), rewritten, "主任-张三");

        assertEquals("人工", after.getSource(), "改写后来源标注「人工」");
        assertEquals(MetricStatus.DRAFT, after.getStatus(), "改写不推进发布状态");
        assertNotEquals(MetricStatus.PUBLISHED, after.getStatus());
        assertEquals(1, after.getRewrites().size(), "留改写记录");
        ExtractContract.RewriteRecord rec = after.getRewrites().get(0);
        assertEquals("主任-张三", rec.getEditor());
        assertNotEquals(rec.getBefore(), rec.getAfter(), "改写记录含改前/改后");
        assertEquals(rewritten, after.getContent());
        assertEquals(draft.getId(), after.getId(), "改写作用于同一份草稿");
    }
}
