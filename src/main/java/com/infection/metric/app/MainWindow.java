package com.infection.metric.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.expr.HumanReadable;
import com.infection.metric.graph.FingerprintEdge;
import com.infection.metric.graph.ImpactAnalysisResult;
import com.infection.metric.graph.ImpactAnalyzer;
import com.infection.metric.graph.ImpactListWriter;
import com.infection.metric.metriclib.BenchmarkSet;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;
import com.infection.metric.recompute.SyntheticDataset;
import com.infection.metric.ui.DetailPanelModel;
import com.infection.metric.ui.DetailPanelView;
import com.infection.metric.ui.DiffItem;
import com.infection.metric.ui.DiffPanelModel;
import com.infection.metric.ui.DiffPanelView;
import com.infection.metric.ui.DiffSection;
import com.infection.metric.ui.DiffSectionType;
import com.infection.metric.ui.ImpactTableView;
import com.infection.metric.ui.ImpactTableModel;
import com.infection.metric.ui.LibraryTreeModel;
import com.infection.metric.ui.LibraryTreeView;
import com.infection.metric.ui.PersistedResultRef;
import com.infection.metric.ui.ThreeColumnLayout;
import com.infection.metric.ui.UiComponents;
import com.infection.metric.ui.UiMetricItem;
import com.infection.metric.ui.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 桌面主窗口：三栏（口径库 / 详情与影响面 / 差异说明），全部数据来自一次真实落盘的复算运行。
 *
 * <p>口径库取规则化合成生成器的 39 条口径；影响面由分母指纹依赖图按选中指标实时展开；
 * 差异说明三段来自已落库的双版本对拍结果（未落库时按门禁拒绝显示，界面不临时另算）。
 * 所有色值 / 间距 / 字号取自 {@link UiTokens}，控件一律经 {@link UiComponents} 构造。</p>
 */
public final class MainWindow {

    private static final String VERSION = ImpactListWriter.BENCHMARK_VERSION;

    private final OfflineSampleArtifacts.Snapshot snapshot;
    private final List<UiMetricItem> items;
    private final Map<String, UiMetricItem> byKey = new LinkedHashMap<>();
    private final LibraryTreeModel treeModel;
    private final ImpactAnalyzer analyzer;
    private final Set<String> analyzerIds = new LinkedHashSet<>();
    private final JFrame frame;
    private final JPanel leftHost;
    private final JPanel detailHost;
    private final JPanel impactHost;
    private final JLabel statusLabel;
    private final JTextField searchField;

    private UiMetricItem selected;

    private MainWindow(OfflineSampleArtifacts.Snapshot snapshot) {
        UiTokens.assertSelfCheck();
        this.snapshot = snapshot;
        this.items = buildItems(snapshot.dataset);
        for (UiMetricItem it : items) byKey.put(it.key(), it);

        BenchmarkSet bs = BenchmarkSet.load();
        this.analyzer = new ImpactAnalyzer();
        for (String code : bs.codes()) {
            FingerprintEdge e = FingerprintEdge.fromBenchmark(bs.byCode(code), VERSION,
                    snapshot.dataset.quarters());
            analyzer.addEntry(e);
            analyzerIds.add(e.getId());
        }
        analyzer.withStoredPeriods(snapshot.dataset.quarters()).build();

        this.treeModel = new LibraryTreeModel(items);
        this.leftHost = host();
        this.detailHost = host();
        this.impactHost = host();
        this.searchField = UiComponents.input();
        this.statusLabel = new JLabel();
        this.frame = buildFrame();

        rebuildTree();
        renderDiff();
        selectCode(OfflineSampleArtifacts.CHANGED_CODE);
    }

    /** 无图形界面时给出明确提示，不创建半截窗口。 */
    public static MainWindow create() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("当前环境无图形界面，请使用 ./run.sh --headless 或 --doctor");
        }
        try {
            return new MainWindow(OfflineSampleArtifacts.computeAndPersist(Paths.get("data")));
        } catch (Exception e) {
            throw new IllegalStateException("装配桌面窗口失败：" + e.getMessage(), e);
        }
    }

    public JFrame frame() { return frame; }
    public JPanel content() { return (JPanel) frame.getContentPane(); }
    public OfflineSampleArtifacts.Snapshot snapshot() { return snapshot; }

    /** 选中一个指标编码（取该编码下排序最靠前的版本），驱动详情与影响面联动。 */
    public void selectCode(String code) {
        UiMetricItem target = items.stream()
                .filter(i -> i.getIndicatorCode().equals(code))
                .findFirst().orElse(null);
        if (target == null) return;
        this.selected = target;
        renderDetail();
        renderImpact();
        SwingUtilities.invokeLater(() -> highlightTree(target));
        updateStatus();
    }

    // ---------------------------------------------------------------- 数据

    private static List<UiMetricItem> buildItems(SyntheticDataset ds) {
        List<UiMetricItem> out = new ArrayList<>();
        int draftIndex = 0;
        for (SyntheticDataset.IndicatorSpec spec : ds.indicators()) {
            MetricDefinition def = spec.toDefinition();
            boolean official = spec.isOfficial();
            MetricStatus status = official ? MetricStatus.PUBLISHED
                    : (draftIndex++ % 3 == 0 ? MetricStatus.PENDING_REVIEW : MetricStatus.DRAFT);
            String denText = spec.getDenominatorKey().isBlank()
                    ? HumanReadable.render(def.getDenominator()) : spec.getDenominatorKey();
            out.add(UiMetricItem.builder(spec.getCode(), spec.getVersion())
                    .title(spec.getTitle())
                    .numeratorText(HumanReadable.render(def.getNumerator()))
                    .denominatorText(denText)
                    .exclusionsText(def.getExclusions().isEmpty()
                            ? "（无排除条件）" : HumanReadable.render(def.getExclusions().get(0)))
                    .deptCodes(spec.getDeptCodes())
                    .periods(spec.getPeriods())
                    .dataSourceFields(List.of("就诊标识", "出院日期", "科室代码",
                            "主要诊断代码", "手术操作代码", "切口等级"))
                    .effectiveFrom("2026-01-01")
                    .effectiveTo("2026-12-31")
                    .status(status)
                    .author("院感科质控专员")
                    .approver(official ? "院感科主任" : "（待审批）")
                    .approvedAt(official ? "2026-01-05 09:30" : "（未审批）")
                    .build());
        }
        return out;
    }

    /** 按选中指标实时展开影响面；不在官方基准图内的本院草稿口径给出真实空态。 */
    private List<com.infection.metric.ui.ImpactRow> impactRows(UiMetricItem item) {
        if (item == null) return List.of();
        String id = item.getIndicatorCode() + "|" + VERSION;
        if (!analyzerIds.contains(id)) return List.of();
        ImpactAnalysisResult result = analyzer.analyze(id, 1);
        List<com.infection.metric.ui.ImpactRow> rows = new ArrayList<>();
        for (ImpactAnalysisResult.ImpactRow row : result.getAffected()) {
            String note = row.getExplanation() == null ? row.getEvidence() : row.getExplanation();
            rows.add(new com.infection.metric.ui.ImpactRow(row.getCode(), row.getName(),
                    row.getVersion(), result.getRecomputeQuarters(), note,
                    row.getSource(), row.getDepth()));
        }
        return rows;
    }

    // ---------------------------------------------------------------- 视图

    private JFrame buildFrame() {
        JFrame f = new JFrame("院感指标口径影响分析 · 口径变更影响与重算差异");
        f.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        f.setSize(1280, 800);
        f.setLocationRelativeTo(null);
        f.setContentPane(buildRoot());
        return f;
    }

    private JPanel buildRoot() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(new Color(UiTokens.CANVAS));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(UiTokens.SURFACE));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(UiTokens.BORDER)),
                BorderFactory.createEmptyBorder(UiTokens.SPACE_2, UiTokens.SPACE_3,
                        UiTokens.SPACE_2, UiTokens.SPACE_3)));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("院感指标口径影响分析");
        title.setFont(new Font(UiTokens.FONT_SANS, Font.BOLD, UiTokens.SIZE_TITLE));
        title.setForeground(new Color(UiTokens.TEXT));
        JLabel subtitle = new JLabel("改一条口径，当场看清哪些指标要跟着重算、历史哪几个季度要重做");
        subtitle.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_BODY));
        subtitle.setForeground(new Color(UiTokens.TEXT_MUTED));
        text.add(title);
        text.add(Box.createVerticalStrut(4));
        text.add(subtitle);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(UiComponents.badge("离线样例 · 已落库", UiTokens.SUCCESS));
        right.add(Box.createHorizontalStrut(UiTokens.SPACE_1));
        JLabel run = new JLabel("运行 " + snapshot.resultId);
        run.setFont(new Font(UiTokens.FONT_MONO, Font.PLAIN, UiTokens.SIZE_CAPTION));
        run.setForeground(new Color(UiTokens.TEXT_MUTED));
        right.add(run);

        header.add(text, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JSplitPane buildBody() {
        ThreeColumnLayout.ColumnSpec spec = ThreeColumnLayout.compute(1280);

        JPanel left = new JPanel(new BorderLayout());
        left.setBackground(new Color(UiTokens.SURFACE));
        left.setBorder(sectionBorder());
        left.add(sectionTitle("口径库 · 按指标编码分组"), BorderLayout.NORTH);
        left.add(leftHost, BorderLayout.CENTER);
        left.add(buildFilters(), BorderLayout.SOUTH);

        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                borderedHost("口径详情 · 分子 / 分母 / 排除条件 / 适用科室与期间", detailHost),
                borderedHost("影响面清单 · 受影响指标与需重算季度", impactHost));
        centerSplit.setDividerLocation(310);
        centerSplit.setResizeWeight(0.45);
        centerSplit.setContinuousLayout(true);
        centerSplit.setBorder(null);

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(new Color(UiTokens.SURFACE));
        center.setBorder(sectionBorder());
        center.add(sectionTitle("详情与影响面"), BorderLayout.NORTH);
        center.add(centerSplit, BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(new Color(UiTokens.SURFACE));
        right.setBorder(sectionBorder());
        right.add(sectionTitle("差异说明 · 分子差 / 分母差 / 口径差"), BorderLayout.NORTH);
        right.add(diffHost = host(), BorderLayout.CENTER);

        // 三栏比例：左栏窄（口径库树）、中右两栏留给表格与长说明，按 1280 宽度配比
        JSplitPane rightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, right);
        rightSplit.setDividerLocation(520);
        rightSplit.setResizeWeight(0.53);
        rightSplit.setContinuousLayout(true);
        rightSplit.setBorder(null);

        JSplitPane root = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightSplit);
        root.setDividerLocation(248);
        root.setResizeWeight(0.2);
        root.setContinuousLayout(true);
        root.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, UiTokens.SPACE_2,
                UiTokens.SPACE_1, UiTokens.SPACE_2));
        root.setBackground(new Color(UiTokens.CANVAS));
        return root;
    }

    private JPanel diffHost;

    private JPanel buildFilters() {
        JPanel bar = new JPanel(new BorderLayout(UiTokens.SPACE_1, 0));
        bar.setBackground(new Color(UiTokens.SURFACE));
        bar.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, UiTokens.SPACE_1,
                UiTokens.SPACE_1, UiTokens.SPACE_1));
        searchField.setToolTipText("按指标编码 / 名称 / 版本搜索");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilters(); }
            public void removeUpdate(DocumentEvent e) { applyFilters(); }
            public void changedUpdate(DocumentEvent e) { applyFilters(); }
        });
        JComboBox<String> status = UiComponents.comboBox("全部状态", "PUBLISHED", "PENDING_REVIEW", "DRAFT");
        status.addActionListener(e -> {
            String picked = (String) status.getSelectedItem();
            if ("全部状态".equals(picked)) treeModel.clearStatusFilter();
            else treeModel.setStatusFilter(MetricStatus.valueOf(picked));
            rebuildTree();
        });
        bar.add(searchField, BorderLayout.CENTER);
        bar.add(status, BorderLayout.EAST);
        return bar;
    }

    private void applyFilters() {
        treeModel.setKeyword(searchField.getText());
        rebuildTree();
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(UiTokens.CANVAS));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(UiTokens.BORDER)),
                BorderFactory.createEmptyBorder(UiTokens.SPACE_1, UiTokens.SPACE_3,
                        UiTokens.SPACE_1, UiTokens.SPACE_3)));
        statusLabel.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_CAPTION));
        statusLabel.setForeground(new Color(UiTokens.TEXT_MUTED));
        bar.add(statusLabel, BorderLayout.WEST);
        JLabel hint = new JLabel("数据来源：内置脱敏合成样例（非院内真实数据）　·　发布须经主任逐条确认",
                SwingConstants.RIGHT);
        hint.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_CAPTION));
        hint.setForeground(new Color(UiTokens.TEXT_MUTED));
        bar.add(hint, BorderLayout.EAST);
        return bar;
    }

    private void updateStatus() {
        int mismatches = snapshot.summary.getFailedSteps().size();
        statusLabel.setText("口径 " + items.size() + " 条　·　"
                + snapshot.dataset.quarters().size() + " 季度　·　"
                + snapshot.summary.getResults().size() + " 次求值　·　oracle 不一致 "
                + mismatches + " 项　·　"
                + (selected == null ? "未选中口径" : "当前 " + selected.getIndicatorCode()
                + " / " + selected.getDefinitionVersion()));
    }

    // ---------------------------------------------------------------- 渲染

    private void rebuildTree() {
        LibraryTreeView view = new LibraryTreeView(treeModel);
        for (int i = 0; i < view.tree().getRowCount(); i++) view.tree().expandRow(i);
        view.tree().addTreeSelectionListener(e -> onTreeSelection(e.getNewLeadSelectionPath()));
        replace(leftHost, view);
    }

    private void onTreeSelection(TreePath path) {
        if (path == null || path.getPathCount() < 3) return;
        Object node = path.getLastPathComponent();
        if (!(node instanceof DefaultMutableTreeNode n)) return;
        String label = String.valueOf(n.getUserObject());
        String version = label.contains(" · ") ? label.substring(0, label.indexOf(" · ")) : label;
        Object groupNode = path.getPathComponent(2);
        String code = String.valueOf(((DefaultMutableTreeNode) groupNode).getUserObject());
        UiMetricItem item = byKey.get(code + "|" + version);
        if (item != null) {
            selected = item;
            renderDetail();
            renderImpact();
            updateStatus();
        }
    }

    private void highlightTree(UiMetricItem item) {
        LibraryTreeView view = findTreeView();
        if (view == null) return;
        String wanted = item.getDefinitionVersion() + " · "
                + LibraryTreeModel.statusBadgeShort(item.getStatus());
        for (int i = 0; i < view.tree().getRowCount(); i++) {
            TreePath p = view.tree().getPathForRow(i);
            if (p != null && p.getPathCount() == 3
                    && wanted.equals(String.valueOf(((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject()))
                    && item.getIndicatorCode().equals(String.valueOf(
                    ((DefaultMutableTreeNode) p.getPathComponent(2)).getUserObject()))) {
                view.tree().setSelectionRow(i);
                view.tree().scrollRowToVisible(i);
                return;
            }
        }
    }

    private LibraryTreeView findTreeView() {
        for (java.awt.Component c : leftHost.getComponents()) {
            if (c instanceof LibraryTreeView v) return v;
        }
        return null;
    }

    private void renderDetail() {
        DetailPanelModel model = new DetailPanelModel();
        if (selected != null) model.select(selected);
        replace(detailHost, new DetailPanelView(model));
    }

    private void renderImpact() {
        ImpactTableModel model = new ImpactTableModel(impactRows(selected));
        replace(impactHost, new ImpactTableView(model));
    }

    private void renderDiff() {
        List<DiffSection> sections = new ArrayList<>();
        List<com.infection.metric.recompute.DiffReportRenderer.Segment> segs = snapshot.segments;
        for (int i = 0; i < segs.size() && i < DiffSectionType.values().length; i++) {
            var seg = segs.get(i);
            DiffSectionType type = DiffSectionType.values()[i];
            List<DiffItem> diffItems = new ArrayList<>();
            int j = 0;
            for (String line : seg.getLines()) {
                diffItems.add(new DiffItem("d" + i + "-" + (j++), type, line,
                        "复算运行 " + snapshot.resultId));
            }
            sections.add(new DiffSection(type, String.valueOf(seg.getNumber()),
                    "复算运行 " + snapshot.resultId + " / " + snapshot.quarter, diffItems));
        }
        PersistedResultRef ref = new PersistedResultRef(snapshot.resultId, snapshot.quarter,
                OfflineSampleArtifacts.NEW_VERSION, OfflineSampleArtifacts.diffReportPersisted());
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(new Color(UiTokens.SURFACE));
        wrap.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, UiTokens.SPACE_1,
                UiTokens.SPACE_1, UiTokens.SPACE_1));
        wrap.add(new DiffPanelView(new DiffPanelModel(ref, sections)), BorderLayout.CENTER);
        if (ref.isDisplayable()) {
            JLabel hint = new JLabel("数字来自复算运行 " + snapshot.resultId + "（季度 "
                    + snapshot.quarter + "，口径版本 " + OfflineSampleArtifacts.NEW_VERSION
                    + "），同输入可再次复算核对");
            hint.setFont(new Font(UiTokens.FONT_SANS, Font.PLAIN, UiTokens.SIZE_CAPTION));
            hint.setForeground(new Color(UiTokens.TEXT_MUTED));
            hint.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, UiTokens.SPACE_1, 0,
                    UiTokens.SPACE_1));
            wrap.add(hint, BorderLayout.NORTH);
        }
        replace(diffHost, wrap);
    }

    // ---------------------------------------------------------------- 小工具

    private static JPanel host() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(UiTokens.SURFACE));
        return p;
    }

    private static void replace(JPanel host, java.awt.Component c) {
        host.removeAll();
        host.add(c, BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
    }

    private static JPanel borderedHost(String title, JPanel body) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(UiTokens.SURFACE));
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(UiTokens.BORDER)));
        p.add(sectionTitle(title), BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    private static JLabel sectionTitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(UiTokens.FONT_SANS, Font.BOLD, UiTokens.SIZE_SUBTITLE));
        l.setForeground(new Color(UiTokens.TEXT));
        l.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, UiTokens.SPACE_2,
                UiTokens.SPACE_1, UiTokens.SPACE_2));
        return l;
    }

    private static javax.swing.border.Border sectionBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(UiTokens.BORDER)),
                BorderFactory.createEmptyBorder(0, 0, 0, 0));
    }

    /** 供截图采集器在 EDT 内等待绘制完成。 */
    public void flush() {
        SwingUtilities.invokeLater(() -> {
            frame.pack();
            frame.setSize(1280, 800);
        });
    }
}
