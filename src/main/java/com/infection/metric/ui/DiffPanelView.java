package com.infection.metric.ui;

import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

/**
 * 差异说明面板的 Swing 视图：三段数字与来源同屏，逐条差异标注确认状态与意见。
 *
 * <p>用 HTML 文本承载长说明，宽度不足时自动换行，不出现横向截断；色值由 {@link UiTokens}
 * 动态生成，不散落硬编码。构造前经 {@link UiGuiGuard#requireGui(String)} 判断 headless；
 * 结果未落库时显示拒绝提示而非临时另算。测试不实例化本类。</p>
 */
public final class DiffPanelView extends JPanel {

    public DiffPanelView(DiffPanelModel model) {
        UiGuiGuard.requireGui("差异说明面板 DiffPanelView");
        setLayout(new BorderLayout());
        setBackground(new Color(UiTokens.SURFACE));

        if (!model.isDisplayable()) {
            JLabel hint = new JLabel("差异说明尚未来自已落库复算结果，暂不显示。");
            hint.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_BODY));
            hint.setForeground(new Color(UiTokens.TEXT_MUTED));
            hint.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, UiTokens.SPACE_1,
                    UiTokens.SPACE_1, UiTokens.SPACE_1));
            add(hint, BorderLayout.NORTH);
            return;
        }

        JEditorPane pane = new JEditorPane("text/html", renderHtml(model));
        pane.setEditable(false);
        pane.setBackground(new Color(UiTokens.SURFACE));
        pane.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, UiTokens.SPACE_1,
                UiTokens.SPACE_1, UiTokens.SPACE_1));
        add(new JScrollPane(pane), BorderLayout.CENTER);
    }

    private static String renderHtml(DiffPanelModel model) {
        String text = hex(UiTokens.TEXT);
        String muted = hex(UiTokens.TEXT_MUTED);
        String warn = hex(UiTokens.WARNING);
        String line = hex(UiTokens.BORDER);
        StringBuilder h = new StringBuilder();
        h.append("<html><body style='font-family:SansSerif;font-size:12px;color:")
                .append(text).append(";margin:0;padding:2px'>");
        for (DiffSection s : model.displaySections()) {
            h.append("<div style='margin-top:10px;padding-bottom:4px;border-bottom:1px solid ")
                    .append(line).append(";font-size:13px;font-weight:bold'>")
                    .append(esc(s.getType().getLabel())).append("：")
                    .append(esc(s.getNumber()))
                    .append("<div style='font-size:11px;font-weight:normal;color:")
                    .append(muted).append("'>来源：").append(esc(s.getSourceRef()))
                    .append("</div></div>");
            for (DiffItem it : s.getItems()) {
                h.append("<div style='margin:5px 0 5px 10px;color:").append(muted).append("'>· ")
                        .append(esc(it.getText()))
                        .append(" <span style='color:").append(warn).append("'>")
                        .append("[").append(esc(String.valueOf(it.getConfirmState())))
                        .append("]</span> ")
                        .append(esc(it.getNote()))
                        .append("</div>");
            }
        }
        h.append("</body></html>");
        return h.toString();
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
