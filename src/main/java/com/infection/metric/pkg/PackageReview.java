package com.infection.metric.pkg;

import com.infection.metric.common.ErrorCodes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 口径包逐条确认与状态机：IMPORTED → REVIEWED → MERGED / REJECTED。
 *
 * <p>上级口径不能自动进门：每条独立记录确认人 / 确认时间（确认与驳回都留痕），
 * 未逐条确认的条目不得进入合并。状态机非法跃迁抛 {@link ErrorCodes#E_PKG_002}
 * 且状态不变；合并前置不满足抛 {@link ErrorCodes#E_AUDIT_001} 且状态不变。</p>
 *
 * <p>模型建议隔离：措辞归并候选与归因结论只是「建议」，单独存放并标注
 * advisory=true / adopted=false，绝不自动确认、绝不自动采纳。</p>
 */
public final class PackageReview {

    /** 单条条目的决策。 */
    public enum Decision { PENDING, CONFIRMED, REJECTED }

    /** 逐条决策留痕：每条独立记录确认人与确认时间。 */
    public static final class EntryDecision {
        private final String entryNo;
        private Decision decision;
        private String reviewer;
        private String decidedAt;

        private EntryDecision(String entryNo) {
            this.entryNo = entryNo;
            this.decision = Decision.PENDING;
            this.reviewer = "";
            this.decidedAt = "";
        }

        public String getEntryNo() { return entryNo; }
        public Decision getDecision() { return decision; }
        public String getReviewer() { return reviewer; }
        public String getDecidedAt() { return decidedAt; }
    }

    /** 模型建议：仅为建议，不自动采纳。 */
    public static final class ModelSuggestion {
        private final String entryNo;
        private final String type;
        private final String content;
        private final boolean advisory = true;
        private final boolean adopted = false;

        public ModelSuggestion(String entryNo, String type, String content) {
            this.entryNo = entryNo == null ? "" : entryNo;
            this.type = type == null ? "" : type;
            this.content = content == null ? "" : content;
        }

        public String getEntryNo() { return entryNo; }
        public String getType() { return type; }
        public String getContent() { return content; }
        public boolean isAdvisory() { return advisory; }
        public boolean isAdopted() { return adopted; }
    }

    /** 确认进度：已确认条数 / 总条数 / 待确认清单 / 已驳回清单。 */
    public static final class ReviewProgress {
        private final int confirmedCount;
        private final int totalCount;
        private final List<String> pendingEntryNos;
        private final List<String> rejectedEntryNos;

        ReviewProgress(int confirmedCount, int totalCount,
                       List<String> pendingEntryNos, List<String> rejectedEntryNos) {
            this.confirmedCount = confirmedCount;
            this.totalCount = totalCount;
            this.pendingEntryNos = List.copyOf(pendingEntryNos);
            this.rejectedEntryNos = List.copyOf(rejectedEntryNos);
        }

        public int getConfirmedCount() { return confirmedCount; }
        public int getTotalCount() { return totalCount; }
        public List<String> getPendingEntryNos() { return pendingEntryNos; }
        public List<String> getRejectedEntryNos() { return rejectedEntryNos; }
        public boolean isAllConfirmed() { return totalCount > 0 && confirmedCount == totalCount; }
    }

    private final ParsedPackage pkg;
    private PackageStatus status;
    private final Map<String, EntryDecision> decisions = new LinkedHashMap<>();
    private final List<ModelSuggestion> suggestions = new ArrayList<>();

    public PackageReview(ParsedPackage pkg) {
        if (pkg == null) throw new com.infection.metric.common.AppException("E_DATA_001", "口径包为空，无法逐条确认");
        this.pkg = pkg;
        this.status = PackageStatus.IMPORTED;
        for (PackageEntry e : pkg.getEntries()) {
            decisions.put(e.getEntryNo(), new EntryDecision(e.getEntryNo()));
        }
    }

    public PackageStatus getStatus() { return status; }
    public ParsedPackage getPackage() { return pkg; }

    /** 逐条确认：记录确认人 + 确认时间（显式时间便于审计复现）。 */
    public void confirm(String entryNo, String reviewer, String decidedAt) {
        requireMutable();
        EntryDecision d = requireEntry(entryNo);
        d.decision = Decision.CONFIRMED;
        d.reviewer = reviewer == null ? "" : reviewer;
        d.decidedAt = decidedAt == null ? "" : decidedAt;
    }

    /** 逐条确认（确认时间取当前时刻）。 */
    public void confirm(String entryNo, String reviewer) {
        confirm(entryNo, reviewer, Instant.now().toString());
    }

    /** 逐条驳回：同样独立留痕，且该条不计入「已确认」，会阻止整包合并。 */
    public void rejectEntry(String entryNo, String reviewer, String decidedAt) {
        requireMutable();
        EntryDecision d = requireEntry(entryNo);
        d.decision = Decision.REJECTED;
        d.reviewer = reviewer == null ? "" : reviewer;
        d.decidedAt = decidedAt == null ? "" : decidedAt;
    }

    public void rejectEntry(String entryNo, String reviewer) {
        rejectEntry(entryNo, reviewer, Instant.now().toString());
    }

    /**
     * 模型建议隔离：措辞归并候选 / 归因结论只登记为建议，界面明确标注，
     * 不自动确认条目、不自动采纳、不影响任何决策状态。
     */
    public void addModelSuggestion(String entryNo, String type, String content) {
        requireEntry(entryNo); // 建议也必须指向真实条目，但不改变其决策
        suggestions.add(new ModelSuggestion(entryNo, type, content));
    }

    public EntryDecision getDecision(String entryNo) { return decisions.get(entryNo); }
    public List<EntryDecision> getDecisions() { return List.copyOf(decisions.values()); }
    public List<ModelSuggestion> getModelSuggestions() { return List.copyOf(suggestions); }

    /** 已确认条目清单（供合并任务使用）。 */
    public List<PackageEntry> getConfirmedEntries() {
        List<PackageEntry> out = new ArrayList<>();
        for (PackageEntry e : pkg.getEntries()) {
            EntryDecision d = decisions.get(e.getEntryNo());
            if (d != null && d.getDecision() == Decision.CONFIRMED) out.add(e);
        }
        return List.copyOf(out);
    }

    /** 逐条确认人映射（条目编号 → 确认人），供合并写端点请求体使用。 */
    public Map<String, String> getConfirmedConfirmers() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, EntryDecision> en : decisions.entrySet()) {
            if (en.getValue().getDecision() == Decision.CONFIRMED) {
                out.put(en.getKey(), en.getValue().getReviewer());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    public ReviewProgress progress() {
        int confirmed = 0;
        List<String> pending = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (PackageEntry e : pkg.getEntries()) {
            EntryDecision d = decisions.get(e.getEntryNo());
            if (d == null || d.getDecision() == Decision.PENDING) pending.add(e.getEntryNo());
            else if (d.getDecision() == Decision.CONFIRMED) confirmed++;
            else rejected.add(e.getEntryNo());
        }
        return new ReviewProgress(confirmed, pkg.totalCount(), pending, rejected);
    }

    /**
     * 状态跃迁：先过状态机（非法 → E_PKG_002 且状态不变），再对 MERGED 追加
     * 「逐条全部确认」前置校验（不满足 → E_AUDIT_001 且状态不变）。校验全通过才写回。
     */
    public void transitionTo(PackageStatus target) {
        status.requireTransition(target);
        if (target == PackageStatus.MERGED) {
            requireAllConfirmed();
        }
        status = target;
    }

    /** 便捷入口：合并成功推进到 MERGED（等价于 transitionTo(MERGED)）。 */
    public void markMerged() { transitionTo(PackageStatus.MERGED); }

    /** 合并前置：状态必须 REVIEWED 且所有条目已逐条确认，否则抛 E_AUDIT_001。 */
    public void requireReadyToMerge() {
        if (status != PackageStatus.REVIEWED) {
            throw ErrorCodes.error(ErrorCodes.E_AUDIT_001,
                    "reason", "口径包当前状态为 " + status + "，须先推进到 REVIEWED 才可合并");
        }
        requireAllConfirmed();
    }

    private void requireAllConfirmed() {
        ReviewProgress p = progress();
        if (!p.isAllConfirmed()) {
            throw ErrorCodes.error(ErrorCodes.E_AUDIT_001,
                    "reason", "存在未逐条确认的条目（待确认 " + p.getPendingEntryNos().size()
                            + " 条 / 驳回 " + p.getRejectedEntryNos().size()
                            + " 条，共 " + p.getTotalCount() + " 条），不得进入合并")
                    .with("confirmed", String.valueOf(p.getConfirmedCount()))
                    .with("total", String.valueOf(p.getTotalCount()));
        }
    }

    private void requireMutable() {
        if (status == PackageStatus.MERGED || status == PackageStatus.REJECTED) {
            throw ErrorCodes.error(ErrorCodes.E_AUDIT_001,
                    "reason", "口径包已进入终态 " + status + "，不可再逐条确认或驳回");
        }
    }

    private EntryDecision requireEntry(String entryNo) {
        EntryDecision d = decisions.get(entryNo);
        if (d == null) {
            throw ErrorCodes.error(ErrorCodes.E_DATA_001,
                    "field", "条目编号=" + entryNo + "（口径包中不存在）");
        }
        return d;
    }
}
