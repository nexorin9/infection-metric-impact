package com.infection.metric.ui;

import com.infection.metric.common.AppException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 任务56（M8-G）：两档布局值、无溢出、四区域三态文案、切换不丢状态、操作反馈与进度。 */
class UiStatesTest {

    @Test
    void 两档宽度的布局值与抽屉折叠() {
        LayoutSpec wide = ResponsiveLayout.layoutFor(1280);
        assertEquals(ResponsiveLayout.MODE_THREE_COLUMN, wide.getMode());
        assertFalse(wide.isDrawerVisible());
        assertEquals(3, wide.getColumnWidths().size(), "1280 宽三栏并列");
        assertEquals(2, wide.getDividerCount());
        assertTrue(wide.getRight() > 0);

        LayoutSpec narrow = ResponsiveLayout.layoutFor(768);
        assertEquals(ResponsiveLayout.MODE_TWO_COLUMN_DRAWER, narrow.getMode());
        assertTrue(narrow.isDrawerVisible(), "768 宽右侧差异说明折叠为抽屉");
        assertEquals(2, narrow.getColumnWidths().size(), "并列两栏");
        assertEquals(0, narrow.getRight(), "右栏进抽屉不占并列宽");
        assertEquals(1, narrow.getDividerCount());
    }

    @Test
    void 两档布局切换后四处区域状态保持() {
        UiStates states = new UiStates();
        states.setState(ViewArea.LIBRARY_TREE, ViewState.LOADING);
        states.setState(ViewArea.DETAIL, ViewState.ERROR);
        states.setState(ViewArea.IMPACT_TABLE, ViewState.CONTENT);
        states.setState(ViewArea.DIFF_PANEL, ViewState.EMPTY);

        ResponsiveLayout.layoutFor(1280);
        ResponsiveLayout.layoutFor(768);
        ResponsiveLayout.layoutFor(1280);

        assertEquals(ViewState.LOADING, states.getState(ViewArea.LIBRARY_TREE));
        assertEquals(ViewState.ERROR, states.getState(ViewArea.DETAIL));
        assertEquals(ViewState.CONTENT, states.getState(ViewArea.IMPACT_TABLE));
        assertEquals(ViewState.EMPTY, states.getState(ViewArea.DIFF_PANEL));
    }

    @Test
    void 四区域三态文案与重试进度可用性() {
        UiStates s = new UiStates();
        for (ViewArea a : ViewArea.values()) {
            StateContent loading = s.stateContent(a, ViewState.LOADING);
            assertTrue(loading.isShowProgress(), a + " 加载显示进度");
            assertFalse(loading.isRetryAvailable());
            assertTrue(loading.getText().contains("加载"));

            StateContent empty = s.stateContent(a, ViewState.EMPTY);
            assertFalse(empty.isShowProgress());
            assertFalse(empty.isRetryAvailable());
            assertTrue(empty.getText().length() > 4, a + " 空态有明确引导文案");

            StateContent error = s.stateContent(a, ViewState.ERROR);
            assertTrue(error.isRetryAvailable(), a + " 错误态可重试");
            assertTrue(error.getText().contains("重试"));
            assertTrue(error.getText().contains("失败"));
        }
        assertTrue(s.stateContent(ViewArea.LIBRARY_TREE, ViewState.EMPTY)
                .getText().contains("导入"));
        assertTrue(s.stateContent(ViewArea.IMPACT_TABLE, ViewState.EMPTY)
                .getText().contains("无需重算"));
    }

    @Test
    void 三态配色统一取自M8A的token() {
        UiStates s = new UiStates();
        StateContent loading = s.stateContent(ViewArea.DETAIL, ViewState.LOADING);
        assertEquals(UiTokens.SURFACE, loading.getBackground());
        assertEquals(UiTokens.TEXT_MUTED, loading.getForeground());
        StateContent error = s.stateContent(ViewArea.DIFF_PANEL, ViewState.ERROR);
        assertEquals(UiTokens.DANGER, error.getForeground(), "错误态用危险语义色");
        assertNotEquals(0, error.getBackground(), "不使用系统默认 0 色值");
        StateContent content = s.stateContent(ViewArea.IMPACT_TABLE, ViewState.CONTENT);
        assertEquals(UiTokens.TEXT, content.getForeground());
    }

    @Test
    void 区域状态默认空态且可独立设置() {
        UiStates s = new UiStates();
        assertEquals(ViewState.EMPTY, s.getState(ViewArea.LIBRARY_TREE));
        s.setState(ViewArea.DETAIL, ViewState.LOADING);
        assertEquals(ViewState.LOADING, s.getState(ViewArea.DETAIL));
        assertEquals(ViewState.EMPTY, s.getState(ViewArea.DIFF_PANEL), "其它区域不受影响");
        assertEquals(4, ViewArea.values().length);
        assertEquals(4, ViewState.values().length);
        assertThrows(AppException.class,
                () -> s.setState(ViewArea.DETAIL, null));
    }

    @Test
    void 操作反馈三态与长任务进度() {
        String running = OperationFeedback.textFor("发布", FeedbackState.RUNNING);
        String success = OperationFeedback.textFor("发布", FeedbackState.SUCCESS);
        String failure = OperationFeedback.textFor("发布", FeedbackState.FAILURE);
        assertTrue(running.contains("正在") && running.contains("发布"));
        assertTrue(success.contains("成功"));
        assertTrue(failure.contains("失败") && failure.contains("运行摘要"));

        OperationFeedback.Progress p = OperationFeedback.progress(2, 8);
        assertEquals(25, p.getPercent());
        assertTrue(p.isShowBar(), "长任务显示进度条");
        assertTrue(p.getText().contains("2/8"));
        assertEquals(8, p.getTotal());
        assertThrows(AppException.class, () -> OperationFeedback.progress(9, 8));
    }

    @Test
    void 各档宽度分配之和不超过容器宽() {
        for (int w : new int[]{768, 1024, 1280, 1440}) {
            LayoutSpec spec = ResponsiveLayout.layoutFor(w);
            assertTrue(spec.total() <= w, w + "px 无溢出");
            assertTrue(spec.fits());
            assertEquals(w, spec.getContainerWidth());
            assertEquals(w, spec.total(), "宽度分配恰好填满容器");
        }
        assertThrows(AppException.class, () -> ResponsiveLayout.layoutFor(10),
                "过小宽度显式报错");
    }
}
