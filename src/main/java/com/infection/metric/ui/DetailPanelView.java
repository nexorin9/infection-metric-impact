package com.infection.metric.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

/**
 * 详情面板的 Swing 视图（薄壳）：把 {@link DetailPanelModel} 的明细行逐条渲染。
 *
 * <p>构造前统一经 {@link UiGuiGuard#requireGui(String)} 判断 headless；未选中口径时显示空态引导。
 * 所有配色 / 字号取自 {@link UiTokens}。</p>
 */
public final class DetailPanelView extends JPanel {

    public DetailPanelView(DetailPanelModel model) {
        UiGuiGuard.requireGui("详情面板 DetailPanelView");
        setLayout(new BorderLayout());
        setBackground(new Color(UiTokens.SURFACE));
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_2, UiTokens.SPACE_2, UiTokens.SPACE_2, UiTokens.SPACE_2));

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(new Color(UiTokens.SURFACE));

        if (!model.hasSelection()) {
            JLabel hint = new JLabel(model.emptyHint());
            hint.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_BODY));
            hint.setForeground(new Color(UiTokens.TEXT_MUTED));
            body.add(hint);
        } else {
            for (DetailPanelModel.DetailLine line : model.lines()) {
                JLabel row = new JLabel(line.getLabel() + "：" + line.getValue());
                boolean heading = line.getLabel().equals("标题");
                row.setFont(new Font(UiTokens.FONT_SANS,
                        heading ? Font.BOLD : Font.PLAIN,
                        heading ? UiTokens.SIZE_SUBTITLE : UiTokens.SIZE_BODY));
                row.setForeground(new Color(UiTokens.TEXT));
                row.setBorder(BorderFactory.createEmptyBorder(2, UiTokens.SPACE_1, 2, UiTokens.SPACE_1));
                body.add(row);
            }
        }
        add(new JScrollPane(body), BorderLayout.CENTER);
    }
}
