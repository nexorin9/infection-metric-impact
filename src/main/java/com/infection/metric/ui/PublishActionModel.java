package com.infection.metric.ui;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.Hashes;
import com.infection.metric.metriclib.MetricStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 发布与确认动作的纯逻辑模型：提交复核 → 主任确认发布 → 回执 / 审计 / 失败反馈。
 *
 * <p>状态机复用底座 {@link MetricStatus}：DRAFT → PENDING_REVIEW → PUBLISHED，禁止跳过复核。
 * 提交复核前必须三项前置全通过（字段对账 / 依赖图无环 / 差异逐条确认），否则按钮禁用并说明原因。
 * 发布必须经人工确认，走 {@link PublishPort} 注入的真实出站端点（幂等键 = sha256(code|version)），
 * 回执当场返回并落审计；出站失败时返回可读原因 + 运行摘要入口，状态不推进、不出现 PUBLISHED。</p>
 */
public final class PublishActionModel {

    private final String indicatorCode;
    private final String definitionVersion;
    private MetricStatus status;
    private final List<AuditEntry> audit = new ArrayList<>();
    private PublishReceipt lastReceipt;
    private PublishOutcome lastOutcome;

    public PublishActionModel(String indicatorCode, String definitionVersion) {
        this(indicatorCode, definitionVersion, MetricStatus.DRAFT);
    }

    public PublishActionModel(String indicatorCode, String definitionVersion, MetricStatus initial) {
        if (indicatorCode == null || indicatorCode.isBlank()
                || definitionVersion == null || definitionVersion.isBlank()) {
            throw AppException.of("E_UI_PUBLISH", "指标编码与口径版本不能为空。");
        }
        this.indicatorCode = indicatorCode;
        this.definitionVersion = definitionVersion;
        this.status = initial == null ? MetricStatus.DRAFT : initial;
    }

    public String getIndicatorCode() { return indicatorCode; }
    public String getDefinitionVersion() { return definitionVersion; }
    public MetricStatus getStatus() { return status; }
    public PublishReceipt getLastReceipt() { return lastReceipt; }
    public PublishOutcome getLastOutcome() { return lastOutcome; }
    public List<AuditEntry> auditRecords() { return List.copyOf(audit); }

    /** 按钮是否可点：状态 DRAFT 且三项前置全通过。 */
    public boolean canSubmitReview(PublishPreCheck pre) {
        return pre != null
                && status == MetricStatus.DRAFT
                && pre.isFieldReconciled()
                && pre.isGraphAcyclic()
                && pre.isDiffConfirmed();
    }

    /** 按钮禁用原因（中文逐条），空列表表示可提交复核。 */
    public List<String> disabledReasons(PublishPreCheck pre) {
        PublishPreCheck p = pre == null
                ? PublishPreCheck.of(false, false, false) : pre;
        List<String> reasons = new ArrayList<>();
        if (status != MetricStatus.DRAFT) {
            reasons.add("当前状态为 " + status + "，只有 DRAFT 可提交复核");
        }
        if (!p.isFieldReconciled()) {
            reasons.add("字段对账未通过：存在缺失或类型不符字段");
        }
        if (!p.isGraphAcyclic()) {
            reasons.add("依赖图存在环：需先断开成环链");
        }
        if (!p.isDiffConfirmed()) {
            reasons.add("差异说明尚未逐条确认");
        }
        return reasons;
    }

    /** 执行提交复核：DRAFT → PENDING_REVIEW，前置不满足时抛 E_PUBLISH_001 且状态不变。 */
    public void submitForReview(PublishPreCheck pre) {
        if (!canSubmitReview(pre)) {
            throw ErrorCodes.error(ErrorCodes.E_PUBLISH_001,
                    "reason", String.join("；", disabledReasons(pre)));
        }
        status.requireTransition(MetricStatus.PENDING_REVIEW);
        status = MetricStatus.PENDING_REVIEW;
        audit.add(new AuditEntry("submit_review", "专员", "DRAFT→PENDING_REVIEW"));
    }

    /** 主任确认并发布：PENDING_REVIEW → PUBLISHED。未经人工确认一律拒绝，绝不出现 PUBLISHED。 */
    public PublishOutcome publish(PublishPort port, boolean humanConfirmed, String approver) {
        if (!humanConfirmed) {
            throw ErrorCodes.error(ErrorCodes.E_AUDIT_001,
                    "reason", "发布须经主任人工确认，未确认不得出现 PUBLISHED");
        }
        if (port == null) {
            throw AppException.of("E_UI_PUBLISH", "发布端口未注入，无法出站发布。");
        }
        if (status != MetricStatus.PENDING_REVIEW) {
            throw ErrorCodes.error(ErrorCodes.E_AUDIT_001,
                    "reason", "只有 PENDING_REVIEW 可发布，当前 " + status);
        }
        status.requireTransition(MetricStatus.PUBLISHED);

        String idem = Hashes.sha256Of(indicatorCode, definitionVersion);
        PublishRequest request = new PublishRequest(
                indicatorCode, definitionVersion, idem, status.name());
        try {
            PublishReceipt receipt = port.publish(request);
            if (receipt == null || receipt.getReceiptId() == null
                    || receipt.getReceiptId().isBlank()) {
                throw AppException.of("E_UI_PUBLISH", "出站未返回有效回执标识，发布不落地。");
            }
            status = MetricStatus.PUBLISHED;
            lastReceipt = receipt;
            audit.add(new AuditEntry("publish", approver == null ? "" : approver,
                    "回执 " + receipt.getReceiptId() + " / 幂等键 " + idem));
            lastOutcome = PublishOutcome.success(receipt, status);
        } catch (AppException e) {
            lastOutcome = PublishOutcome.failure(e.render(), summaryEntry(), status);
        }
        return lastOutcome;
    }

    /** 发布回执审计记录数（验收：发布后审计表有回执记录）。 */
    public int receiptRecordCount() {
        return (int) audit.stream().filter(a -> a.getAction().equals("publish")).count();
    }

    private String summaryEntry() {
        return "运行摘要入口：out/runs/2026/run-latest/summary.json（指标 " + indicatorCode
                + " 的失败步骤与未发布口径清单可在此查看）";
    }

    /** 审计条目：动作 / 操作人 / 明细（含回执标识与幂等键）。 */
    public static final class AuditEntry {
        private final String action;
        private final String actor;
        private final String detail;
        public AuditEntry(String action, String actor, String detail) {
            this.action = action;
            this.actor = actor;
            this.detail = detail;
        }
        public String getAction() { return action; }
        public String getActor() { return actor; }
        public String getDetail() { return detail; }
    }
}
