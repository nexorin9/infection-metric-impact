package com.infection.metric.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

/**
 * 发布动作的 Swing 视图（薄壳）：提交复核 / 主任确认并发布两个按钮，按前置检查决定禁用态并展示原因。
 *
 * <p>构造前统一经 {@link UiGuiGuard#requireGui(String)} 判断 headless；按钮由 M8-A 组件工厂统一取 token。
 * 测试不实例化本类。</p>
 */
public final class PublishActionView extends JPanel {

    public PublishActionView(PublishActionModel model, PublishPreCheck pre) {
        UiGuiGuard.requireGui("发布动作 PublishActionView");
        setLayout(new BorderLayout());
        setBackground(new Color(UiTokens.SURFACE));
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_1, UiTokens.SPACE_1, UiTokens.SPACE_1, UiTokens.SPACE_1));

        JButton submit = UiComponents.button("提交复核");
        submit.setEnabled(model.canSubmitReview(pre));
        JButton publish = UiComponents.button("主任确认并发布");

        JLabel reason = new JLabel(model.disabledReasons(pre).isEmpty()
                ? "前置检查通过，可提交复核。"
                : String.join("；", model.disabledReasons(pre)));
        reason.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_CAPTION));
        reason.setForeground(new Color(UiTokens.TEXT_MUTED));

        add(submit, BorderLayout.WEST);
        add(publish, BorderLayout.EAST);
        add(reason, BorderLayout.SOUTH);
    }
}
