package com.infection.metric.ui;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

/**
 * 依赖树的 Swing 视图（薄壳）：把 {@link DependencyTreeModel} 的层级行渲染成 JTree。
 *
 * <p>构造前统一经 {@link UiGuiGuard#requireGui(String)} 判断 headless；等宽字体用于对齐指纹与深度标注。
 * 测试不实例化本类。</p>
 */
public final class DependencyTreeView extends JPanel {

    public DependencyTreeView(DependencyTreeModel model, int maxDepth) {
        UiGuiGuard.requireGui("依赖树 DependencyTreeView");
        setLayout(new BorderLayout());
        setBackground(new Color(UiTokens.SURFACE));

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("口径依赖层级");
        for (String line : model.renderLines(maxDepth)) {
            root.add(new DefaultMutableTreeNode(line));
        }
        JTree tree = new JTree(root);
        tree.setFont(new Font(UiTokens.FONT_MONO, Font.PLAIN, UiTokens.SIZE_MONO));
        tree.setForeground(new Color(UiTokens.TEXT));
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }
}
