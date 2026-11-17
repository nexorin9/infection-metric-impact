package com.infection.metric.pkg;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务48：口径包逐条确认与状态机。 */
class PackageReviewTest {

    private static PackageEntry entry(String no) {
        return PackageEntry.builder()
                .entryNo(no).indicatorCode("HAI-" + no).indicatorName("指标 " + no)
                .definition("口径定义 " + no)
                .numerator("分子文本").denominator("分母文本")
                .deptCodes(List.of("0301"))
                .effectiveFrom("2026-01-01").effectiveTo("2026-12-31")
                .build();
    }

    private static PackageReview review() {
        ParsedPackage pkg = new ParsedPackage("PKG-2026Q1", "json", "region",
                List.of(entry("E01"), entry("E02")));
        return new PackageReview(pkg);
    }

    @Test
    void 未逐条确认不能进入合并且状态不变() {
        PackageReview r = review();
        r.transitionTo(PackageStatus.REVIEWED); // 合法跃迁

        AppException ex = assertThrows(AppException.class, () -> r.transitionTo(PackageStatus.MERGED));

        assertEquals(PackageStatus.REVIEWED, r.getStatus(), "前置不满足时状态保持不变");
        assertTrue(ex.render().contains("未逐条确认"), "须说明未逐条确认不得合并");
        assertEquals(0, r.progress().getConfirmedCount(), "此时确认条数为 0");
        assertEquals(2, r.progress().getPendingEntryNos().size(), "两条都待确认");
    }

    @Test
    void 非法跃迁被拒且状态不变() {
        PackageReview r = review();

        AppException ex1 = assertThrows(AppException.class, () -> r.transitionTo(PackageStatus.MERGED));
        assertEquals(ErrorCodes.E_PKG_002, ex1.getCode(), "IMPORTED 直接跳 MERGED 属非法跃迁");
        assertEquals(PackageStatus.IMPORTED, r.getStatus(), "非法跃迁后状态不变");

        r.transitionTo(PackageStatus.REVIEWED);
        AppException ex2 = assertThrows(AppException.class, () -> r.transitionTo(PackageStatus.IMPORTED));
        assertEquals(ErrorCodes.E_PKG_002, ex2.getCode(), "REVIEWED 回退 IMPORTED 也非法");
        assertEquals(PackageStatus.REVIEWED, r.getStatus(), "再次非法跃迁后状态仍不变");
    }

    @Test
    void 每条确认独立留痕确认人与确认时间() {
        PackageReview r = review();
        r.confirm("E01", "张主任", "2026-11-12T09:00:00Z");
        r.confirm("E02", "李专员", "2026-11-12T10:30:00Z");

        PackageReview.EntryDecision d1 = r.getDecision("E01");
        PackageReview.EntryDecision d2 = r.getDecision("E02");
        assertEquals(PackageReview.Decision.CONFIRMED, d1.getDecision());
        assertEquals("张主任", d1.getReviewer());
        assertEquals("2026-11-12T09:00:00Z", d1.getDecidedAt());
        assertEquals("李专员", d2.getReviewer(), "第二条确认人独立记录");
        assertNotEquals(d1.getDecidedAt(), d2.getDecidedAt(), "每条确认时间独立留痕");

        PackageReview.ReviewProgress p = r.progress();
        assertEquals(2, p.getConfirmedCount());
        assertEquals(2, p.getTotalCount());
        assertTrue(p.getPendingEntryNos().isEmpty());

        r.transitionTo(PackageStatus.REVIEWED);
        r.transitionTo(PackageStatus.MERGED);
        assertEquals(PackageStatus.MERGED, r.getStatus(), "全部确认后可合并");
        assertEquals(2, r.getConfirmedEntries().size(), "合并取全部已确认条目");
        assertEquals("张主任", r.getConfirmedConfirmers().get("E01"), "逐条确认人随合并可反查");
    }

    @Test
    void 模型建议隔离_仅建议且不自动采纳() {
        PackageReview r = review();
        r.addModelSuggestion("E01", "merge_aliases", "候选：指标 E01 与 E02 措辞可归并");
        r.addModelSuggestion("E01", "attribute_diff", "归因结论：分母口径（候选）");

        assertEquals(2, r.getModelSuggestions().size());
        for (PackageReview.ModelSuggestion s : r.getModelSuggestions()) {
            assertTrue(s.isAdvisory(), "模型建议须标注 advisory");
            assertFalse(s.isAdopted(), "模型建议不得自动采纳");
        }
        assertEquals(PackageReview.Decision.PENDING, r.getDecision("E01").getDecision(),
                "模型建议不自动改变条目决策");
        assertEquals(0, r.progress().getConfirmedCount(), "模型建议不计入确认进度");
    }

    @Test
    void 驳回同样留痕并阻止整包合并() {
        PackageReview r = review();
        r.confirm("E01", "张主任", "2026-11-12T09:00:00Z");
        r.rejectEntry("E02", "李专员", "2026-11-12T10:30:00Z");

        assertEquals(PackageReview.Decision.REJECTED, r.getDecision("E02").getDecision());
        assertEquals("李专员", r.getDecision("E02").getReviewer(), "驳回也留确认人");
        assertEquals(1, r.progress().getConfirmedCount());
        assertTrue(r.progress().getRejectedEntryNos().contains("E02"));

        r.transitionTo(PackageStatus.REVIEWED);
        assertThrows(AppException.class, () -> r.transitionTo(PackageStatus.MERGED),
                "存在驳回条目时不得进入合并");
        assertEquals(PackageStatus.REVIEWED, r.getStatus(), "被拒合并后状态仍为 REVIEWED");
    }
}
