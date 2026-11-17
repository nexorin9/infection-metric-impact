package com.infection.metric.ui;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务53（M8-D）：三段齐全、逐条确认门禁、未落库拒绝显示、数字可复算提示。 */
class UiDiffTest {

    private static PersistedResultRef saved() {
        return new PersistedResultRef("run-20261109-044107", "2026-Q1", "v2", true);
    }

    private static DiffSection section(DiffSectionType type, String number,
                                       String ref, String... texts) {
        List<DiffItem> items = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            items.add(new DiffItem(type.name() + "-" + i, type, texts[i], ref));
        }
        return new DiffSection(type, number, ref, items);
    }

    private static List<DiffSection> threeSections() {
        return List.of(
                section(DiffSectionType.NUMERATOR, "-3 例",
                        "口径 HAI-01 v2 / 变更条目#3", "分子 12→9"),
                section(DiffSectionType.DENOMINATOR, "+0 例",
                        "口径 HAI-01 v2 / 变更条目#4", "分母 120→120"),
                section(DiffSectionType.DEFINITION, "2 条条件变更",
                        "口径 HAI-01 v2 / 条件级差异清单",
                        "新增排除：切口等级≠Ⅲ", "收窄期间：2026-Q1"));
    }

    private static DiffPanelModel panel(PersistedResultRef ref) {
        return new DiffPanelModel(ref, threeSections());
    }

    @Test
    void 三段齐全且数字与来源引用同屏() {
        DiffPanelModel m = panel(saved());
        List<DiffSection> ss = m.displaySections();
        assertEquals(3, ss.size());
        assertEquals(List.of("分子差", "分母差", "口径差"),
                ss.stream().map(s -> s.getType().getLabel()).toList());
        assertTrue(ss.stream().allMatch(s ->
                !s.getNumber().isBlank() && !s.getSourceRef().isBlank()),
                "每段都有数字与来源引用");
        assertTrue(m.threeSectionsComplete());
        assertEquals(4, m.totalCount());
    }

    @Test
    void 缺一段构造时显式报错而非留空() {
        AppException ex = assertThrows(AppException.class,
                () -> new DiffPanelModel(saved(), threeSections().subList(0, 2)));
        assertEquals("E_UI_DIFF", ex.getCode());
        assertTrue(ex.getMessage().contains("缺"));
        assertTrue(ex.getMessage().contains("口径差"));
    }

    @Test
    void 初始未确认差异阻止提交复核() {
        DiffPanelModel m = panel(saved());
        assertTrue(m.isDisplayable());
        assertEquals(4, m.unconfirmedCount(), "四条差异初始全部未确认");
        assertFalse(m.canSubmitReview(), "存在未确认差异时阻止提交复核");
        AppException ex = assertThrows(AppException.class, m::submitReview);
        assertEquals(ErrorCodes.E_AUDIT_001, ex.getCode());
        assertTrue(ex.render().contains("未确认"));
    }

    @Test
    void 逐条全部确认后可提交且意见留痕() {
        DiffPanelModel m = panel(saved());
        for (DiffItem it : m.items()) {
            m.confirm(it.getId(), ConfirmState.CONFIRMED, "核对无误");
        }
        assertEquals(0, m.unconfirmedCount());
        assertTrue(m.canSubmitReview());
        assertDoesNotThrow(m::submitReview);
        assertEquals("核对无误", m.items().get(0).getNote());
        assertEquals(ConfirmState.CONFIRMED, m.items().get(0).getConfirmState());
    }

    @Test
    void 标为有疑问仍视为未确认并阻止提交() {
        DiffPanelModel m = panel(saved());
        for (DiffItem it : m.items()) {
            m.confirm(it.getId(), ConfirmState.CONFIRMED, "核对无误");
        }
        DiffItem q = m.items().get(1);
        m.confirm(q.getId(), ConfirmState.QUESTIONED, "数字来源需再核");
        assertEquals(1, m.unconfirmedCount(), "有疑问计入未确认");
        assertFalse(m.canSubmitReview());
        assertEquals(ConfirmState.QUESTIONED, q.getConfirmState());
        assertEquals("数字来源需再核", q.getNote());
        assertThrows(AppException.class, m::submitReview);
    }

    @Test
    void 未落库或运行标识为空时拒绝显示() {
        DiffPanelModel notSaved = panel(
                new PersistedResultRef("run-1", "2026-Q1", "v2", false));
        assertFalse(notSaved.isDisplayable());
        assertThrows(AppException.class, notSaved::displaySections);
        assertThrows(AppException.class, notSaved::recomputeHint);
        assertFalse(notSaved.canSubmitReview());

        DiffPanelModel blankId = panel(
                new PersistedResultRef("", "2026-Q1", "v2", true));
        assertFalse(blankId.isDisplayable());
        assertThrows(AppException.class, blankId::displaySections);
    }

    @Test
    void 复算提示写明运行标识季度与口径版本() {
        String hint = panel(saved()).recomputeHint();
        assertTrue(hint.contains("run-20261109-044107"), "含运行标识");
        assertTrue(hint.contains("2026-Q1"), "含季度");
        assertTrue(hint.contains("v2"), "含口径版本");
        assertTrue(hint.contains("复算"), "说明数字可复算核对");
    }
}
