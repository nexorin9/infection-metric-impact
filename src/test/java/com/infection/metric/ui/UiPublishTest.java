package com.infection.metric.ui;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.Hashes;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务54（M8-E）：前置检查、状态机、人工确认、幂等键、回执审计、失败反馈、二次确认。 */
class UiPublishTest {

    private static PublishActionModel draft() {
        return new PublishActionModel("HAI-01", "v2");
    }

    private static PublishPort okPort() {
        return req -> new PublishReceipt("RCP-001",
                "POST /api/v1/indicator-definitions", true, 201,
                "2026-07-01T10:00:00", "{\"id\":\"qc-1\"}");
    }

    @Test
    void 前置三项任一不满足则按钮禁用且说明原因() {
        PublishActionModel m = draft();
        PublishPreCheck bad = PublishPreCheck.of(false, true, true);
        assertFalse(m.canSubmitReview(bad));
        List<String> reasons = m.disabledReasons(bad);
        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0).contains("字段对账"), "原因点名字段对账");

        AppException ex = assertThrows(AppException.class, () -> m.submitForReview(bad));
        assertEquals(ErrorCodes.E_PUBLISH_001, ex.getCode());
        assertEquals(MetricStatus.DRAFT, m.getStatus(), "前置不满足时状态不变");

        assertFalse(m.canSubmitReview(PublishPreCheck.of(true, false, true)), "依赖图成环禁用");
        assertFalse(m.canSubmitReview(PublishPreCheck.of(true, true, false)), "差异未确认禁用");
        assertTrue(m.canSubmitReview(PublishPreCheck.allPassed()), "三项全通过才可提交");
    }

    @Test
    void 提交复核成功且禁止跳过复核直接发布() {
        PublishActionModel m = draft();
        m.submitForReview(PublishPreCheck.allPassed());
        assertEquals(MetricStatus.PENDING_REVIEW, m.getStatus());
        assertEquals(1, m.auditRecords().size());
        assertEquals("submit_review", m.auditRecords().get(0).getAction());

        AppException ex = assertThrows(AppException.class,
                () -> MetricStatus.DRAFT.requireTransition(MetricStatus.PUBLISHED));
        assertEquals(ErrorCodes.E_STATE_001, ex.getCode(), "DRAFT→PUBLISHED 非法跃迁被拒");
        assertTrue(ex.render().contains("DRAFT"));
    }

    @Test
    void 未经人工确认不得出现PUBLISHED() {
        PublishActionModel m = draft();
        m.submitForReview(PublishPreCheck.allPassed());
        AppException ex = assertThrows(AppException.class,
                () -> m.publish(okPort(), false, "主任B"));
        assertEquals(ErrorCodes.E_AUDIT_001, ex.getCode());
        assertEquals(MetricStatus.PENDING_REVIEW, m.getStatus());
        assertNotEquals(MetricStatus.PUBLISHED, m.getStatus());
        assertTrue(m.auditRecords().stream().noneMatch(a -> a.getAction().equals("publish")));
    }

    @Test
    void 人工确认发布成功带回执幂等键并落审计() {
        PublishActionModel m = draft();
        m.submitForReview(PublishPreCheck.allPassed());
        PublishRequest[] captured = new PublishRequest[1];
        PublishPort port = req -> {
            captured[0] = req;
            return new PublishReceipt("RCP-001",
                    "POST /api/v1/indicator-definitions", true, 201,
                    "2026-07-01T10:00:00", "{}");
        };

        PublishOutcome out = m.publish(port, true, "主任B");
        assertTrue(out.isSuccess());
        assertEquals(MetricStatus.PUBLISHED, m.getStatus());
        assertNotNull(out.getReceipt(), "发布回执当场显示");
        assertEquals("RCP-001", out.getReceipt().getReceiptId());
        assertEquals("RCP-001", m.getLastReceipt().getReceiptId());
        assertEquals(Hashes.sha256Of("HAI-01", "v2"), captured[0].getIdempotencyKey(),
                "幂等键 = sha256(code|version)");
        assertEquals(64, captured[0].getIdempotencyKey().length());
        assertTrue(m.auditRecords().stream().anyMatch(a ->
                a.getAction().equals("publish") && a.getDetail().contains("RCP-001")),
                "发布后审计表有回执记录");
        assertEquals(1, m.receiptRecordCount());
    }

    @Test
    void 出站失败展示可读原因与运行摘要入口且状态不变() {
        PublishActionModel m = draft();
        m.submitForReview(PublishPreCheck.allPassed());
        PublishPort failing = req -> {
            throw ErrorCodes.error(ErrorCodes.E_OUTBOUND_002,
                    "endpoint", "POST /api/v1/indicator-definitions");
        };

        PublishOutcome out = m.publish(failing, true, "主任B");
        assertFalse(out.isSuccess());
        assertTrue(out.getFailureReason().contains("超时")
                || out.getFailureReason().contains("E_OUTBOUND_002"), "失败原因可读、非泛化");
        assertTrue(out.getSummaryEntry().contains("运行摘要"), "给出运行摘要入口");
        assertTrue(out.getSummaryEntry().contains("HAI-01"));
        assertEquals(MetricStatus.PENDING_REVIEW, m.getStatus(), "失败不推进状态");
        assertNull(out.getReceipt());
        assertNull(m.getLastReceipt());
    }

    @Test
    void 破坏性操作需二次确认且文案写明影响指标与季度() {
        assertTrue(ConfirmationGate.requiresSecondConfirmation(DestructiveAction.PUBLISH));
        assertTrue(ConfirmationGate.requiresSecondConfirmation(DestructiveAction.RETIRE));
        assertTrue(ConfirmationGate.requiresSecondConfirmation(DestructiveAction.DELETE_DRAFT));
        assertFalse(ConfirmationGate.requiresSecondConfirmation(DestructiveAction.OPEN_DETAIL),
                "查看详情非破坏性，无需二次确认");

        String text = ConfirmationGate.confirmationText(DestructiveAction.PUBLISH,
                List.of("HAI-01", "HAI-02"), List.of("2026-Q1", "2026-Q2"));
        assertTrue(text.contains("HAI-01"), "写明将影响的指标");
        assertTrue(text.contains("2026-Q1"), "写明涉及的季度");
        assertTrue(text.contains("发布"));
        assertTrue(text.contains("二次确认"));
    }

    @Test
    void 停用与删除草稿的确认文案写明后果与季度空态() {
        String retire = ConfirmationGate.confirmationText(DestructiveAction.RETIRE,
                List.of("HAI-01"), List.of());
        assertTrue(retire.contains("停用"));
        assertTrue(retire.contains("无历史季度"), "无交集时明确写无历史季度");

        String del = ConfirmationGate.confirmationText(DestructiveAction.DELETE_DRAFT,
                List.of("HAI-01"), List.of("2026-Q1"));
        assertTrue(del.contains("删除本地草稿"));
        assertTrue(del.contains("2026-Q1"));
        assertTrue(del.contains("不可恢复"));
    }
}
