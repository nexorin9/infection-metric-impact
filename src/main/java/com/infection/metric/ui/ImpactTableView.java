package com.infection.metric.ui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

/**
 * 影响面表的 Swing 视图（薄壳）：把 {@link ImpactTableModel} 的行渲染成 JTable。
 *
 * <p>构造前统一经 {@link UiGuiGuard#requireGui(String)} 判断 headless；空态显示「无需重算」引导。
 * 配色 / 字号取自 {@link UiTokens}。测试不实例化本类。</p>
 */
public final class ImpactTableView extends JPanel {

    public ImpactTableView(ImpactTableModel model) {
        UiGuiGuard.requireGui("影响面表 ImpactTableView");
        setLayout(new BorderLayout());
        setBackground(new Color(UiTokens.SURFACE));

        DefaultTableModel tm = new DefaultTableModel(model.columns().toArray(), 0);
        for (String[] cells : model.cells()) {
            tm.addRow(cells);
        }
        JTable table = new JTable(tm) {
            /** 列宽不足时，悬停可读到未截断的完整说明与来源。 */
            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int col = columnAtPoint(e.getPoint());
                if (row >= 0 && col >= 0) {
                    Object v = getValueAt(row, col);
                    return v == null ? null : String.valueOf(v);
                }
                return super.getToolTipText(e);
            }
        };
        table.setRowHeight(UiTokens.SPACE_4);
        table.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_BODY));
        table.setGridColor(new Color(UiTokens.BORDER));
        table.setSelectionBackground(new Color(UiTokens.SELECTED));
        // 列宽按业务重要性分配：编码 / 名称 / 版本 / 季度宽一些，说明与来源可悬停看全
        int[] widths = {96, 112, 62, 104, 130, 120, 44};
        for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        add(new JScrollPane(table), BorderLayout.CENTER);

        if (model.isEmpty()) {
            JLabel hint = new JLabel(model.emptyHint());
            hint.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_BODY));
            hint.setForeground(new Color(UiTokens.TEXT_MUTED));
            add(hint, BorderLayout.NORTH);
        }
    }
}
