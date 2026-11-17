package com.infection.metric.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

/**
 * 口径库树的 Swing 视图（薄壳）：把 {@link LibraryTreeModel} 的分组渲染成 JTree。
 *
 * <p>构造前统一经 {@link UiGuiGuard#requireGui(String)} 判断 headless，无图形界面时抛明确提示，
 * 不创建真实窗口。所有配色 / 字号取自 {@link UiTokens}。</p>
 */
public final class LibraryTreeView extends JPanel {

    private final JTree tree;

    /** 暴露树控件供主窗口挂选中联动；视图本身不处理业务动作。 */
    public JTree tree() { return tree; }

    public LibraryTreeView(LibraryTreeModel model) {
        UiGuiGuard.requireGui("口径库树 LibraryTreeView");
        setLayout(new BorderLayout());
        setBackground(new Color(UiTokens.SURFACE));
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_1, UiTokens.SPACE_1, UiTokens.SPACE_1, UiTokens.SPACE_1));

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("口径库（按指标编码分组）");
        for (LibraryTreeModel.Group g : model.visibleGroups()) {
            DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(g.getCode());
            for (UiMetricItem it : g.getItems()) {
                groupNode.add(new DefaultMutableTreeNode(
                        it.getDefinitionVersion() + " · "
                                + LibraryTreeModel.statusBadgeShort(it.getStatus())));
            }
            root.add(groupNode);
        }

        JTree built = new JTree(root);
        this.tree = built;
        JTree tree = built;
        tree.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_BODY));
        tree.setForeground(new Color(UiTokens.TEXT));
        add(new JScrollPane(tree), BorderLayout.CENTER);

        // 空库时给出「先导入或新建口径」的引导；非空时由加载态三态组件接管提示。
        if (model.isEmpty()) {
            JLabel hint = new JLabel(model.emptyHint());
            hint.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_CAPTION));
            hint.setForeground(new Color(UiTokens.TEXT_MUTED));
            add(hint, BorderLayout.NORTH);
        }
    }
}
