package com.infection.metric.ui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

/**
 * 三态面板的 Swing 视图（薄壳）：按 {@link StateContent} 渲染文案、进度条与重试按钮。
 *
 * <p>构造前统一经 {@link UiGuiGuard#requireGui(String)} 判断 headless；配色 / 字号 / 按钮一律取自
 * M8-A token 与组件工厂。测试不实例化本类。</p>
 */
public final class ViewStatePanel extends JPanel {

    public ViewStatePanel(StateContent content) {
        UiGuiGuard.requireGui("三态面板 ViewStatePanel");
        setLayout(new BorderLayout());
        setBackground(new Color(content.getBackground()));

        JLabel label = new JLabel(content.getText());
        label.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_BODY));
        label.setForeground(new Color(content.getForeground()));
        add(label, BorderLayout.CENTER);

        if (content.isShowProgress()) {
            JProgressBar bar = new JProgressBar();
            bar.setIndeterminate(true);
            add(bar, BorderLayout.SOUTH);
        }
        if (content.isRetryAvailable()) {
            add(UiComponents.button("重试"), BorderLayout.EAST);
        }
    }
}
