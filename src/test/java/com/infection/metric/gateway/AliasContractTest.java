package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务44：同指标多措辞归并契约，本地 schema 校验与候选采纳流程。
 */
class AliasContractTest {

    private static ObjectNode pair(String left, String right, double confidence, String reason) {
        ObjectNode o = JsonSupport.object();
        o.put("left_id", left);
        o.put("right_id", right);
        o.put("confidence", confidence);
        o.put("reason", reason);
        return o;
    }

    private static AppException expectSchemaFail(JsonNode response) {
        return assertThrows(AppException.class,
                () -> new AliasContract().validateResponse(response, Fixtures.aliasPayload()));
    }

    @Test
    void 合规归并响应通过校验() {
        ArrayNode resp = Fixtures.aliasResponse();
        assertDoesNotThrow(() -> new AliasContract().validateResponse(resp, Fixtures.aliasPayload()));
        assertEquals("merge_aliases", new AliasContract().task());
        assertEquals("1.0", new AliasContract().schemaVersion());
        assertEquals("c1", resp.get(0).path("left_id").asText());
    }

    @Test
    void 越界置信度空reason引用不存在id一律判E_LLM_004() {
        ArrayNode over = JsonSupport.array();
        over.add(pair("c1", "c2", 1.5, "合理"));
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(over).getCode(), "置信度 > 1");

        ArrayNode under = JsonSupport.array();
        under.add(pair("c1", "c2", -0.2, "合理"));
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(under).getCode(), "置信度 < 0");

        ArrayNode emptyReason = JsonSupport.array();
        emptyReason.add(pair("c1", "c2", 0.9, "   "));
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(emptyReason).getCode(), "空 reason");

        ArrayNode ghost = JsonSupport.array();
        ghost.add(pair("ghost", "c2", 0.9, "合理"));
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(ghost).getCode(), "引用不存在的 left_id");

        ArrayNode notNumber = JsonSupport.array();
        ObjectNode p = pair("c1", "c2", 0.9, "合理");
        p.put("confidence", "high");
        notNumber.add(p);
        assertEquals(ErrorCodes.E_LLM_004, expectSchemaFail(notNumber).getCode(), "置信度非数值");
    }

    @Test
    void 候选先入待确认清单_采纳后才影响对齐结果() {
        ArrayNode resp = JsonSupport.array();
        resp.add(pair("c1", "c2", 0.9, "同义"));
        resp.add(pair("c2", "c3", 0.8, "同义"));
        new AliasContract().validateResponse(resp, Fixtures.aliasPayload());

        AliasContract.ReviewQueue queue = new AliasContract.ReviewQueue();
        List<AliasContract.AliasCandidate> added = queue.addFromResponse(resp);
        assertEquals(2, added.size());
        assertEquals(2, queue.pending().size(), "候选先入待确认清单");
        assertTrue(queue.alignmentPairs().isEmpty(), "未采纳前不影响对齐结果");

        String firstId = added.get(0).getId();
        String secondId = added.get(1).getId();
        AliasContract.AliasCandidate accepted = queue.accept(firstId, "主任-李四");
        assertEquals(AliasContract.State.ACCEPTED, accepted.getState());
        assertEquals("主任-李四", accepted.getReviewer());
        assertTrue(accepted.getReviewedAtMillis() > 0);
        assertEquals(1, queue.alignmentPairs().size(), "采纳后才影响对齐结果");
        assertEquals("c1", queue.alignmentPairs().get(0).getLeftId());

        AliasContract.AliasCandidate rejected = queue.reject(secondId, "主任-李四");
        assertEquals(AliasContract.State.REJECTED, rejected.getState());
        assertEquals(1, queue.alignmentPairs().size(), "驳回的不进入对齐结果");
    }

    @Test
    void 替身返回越界置信度_校验失败且不重试() {
        FakeTransport fake = new FakeTransport();
        ArrayNode resp = JsonSupport.array();
        resp.add(pair("c1", "c2", 3.0, "越界"));
        fake.body = JsonSupport.compact(resp);
        ModelGateway gw = Fixtures.gateway(fake);

        AppException ex = assertThrows(AppException.class,
                () -> gw.call(AliasContract.TASK, "1.0", Fixtures.aliasPayload()));
        assertEquals(ErrorCodes.E_LLM_004, ex.getCode(), "越界置信度 → schema 校验失败");
        assertEquals(1, fake.calls, "schema 校验失败不重试");
    }
}
