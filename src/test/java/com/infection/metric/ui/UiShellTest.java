package com.infection.metric.ui;

import com.infection.metric.common.AppException;
import com.infection.metric.metriclib.MetricStatus;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 任务51（M8-B）：口径库树分组 / 状态过滤 / 搜索 / 空态，详情面板字段，三栏布局计算。 */
class UiShellTest {

    private static UiMetricItem item(String code, String version, String title, MetricStatus status) {
        return UiMetricItem.builder(code, version)
                .title(title)
                .status(status)
                .numeratorText("感染部位 = 呼吸道")
                .denominatorText("同期出院患者病历总数")
                .exclusionsText("切口等级 ≠ Ⅲ")
                .deptCodes(List.of("0301", "0302"))
                .periods(List.of("2026-Q1", "2026-Q2"))
                .dataSourceFields(List.of("hai.infection_report.infection_site", "emr.encounter.discharge_date"))
                .effectiveFrom("2026-01-01")
                .effectiveTo("2026-06-30")
                .author("专员A")
                .approver("主任B")
                .approvedAt("2026-07-01 10:00")
                .build();
    }

    private static List<UiMetricItem> dataset() {
        return List.of(
                item("HAI-01", "v1", "院感发病率", MetricStatus.DRAFT),
                item("HAI-01", "v2", "院感发病率(改)", MetricStatus.PENDING_REVIEW),
                item("HAI-01", "v3", "院感发病率(发布)", MetricStatus.PUBLISHED),
                item("HAI-02", "v1", "手术部位感染率", MetricStatus.PUBLISHED),
                item("MRQ-01", "v1", "病案质控指标", MetricStatus.RETIRED));
    }

    @Test
    void 按指标编码分组且同指标多版本共存() {
        LibraryTreeModel m = new LibraryTreeModel(dataset());
        List<LibraryTreeModel.Group> gs = m.groups();
        assertEquals(3, gs.size(), "HAI-01 / HAI-02 / MRQ-01 三组");
        LibraryTreeModel.Group hai01 = gs.stream()
                .filter(g -> g.getCode().equals("HAI-01")).findFirst().orElseThrow();
        assertEquals(3, hai01.getVersionCount(), "同指标三个版本并存");
        assertEquals(List.of("v1", "v2", "v3"),
                hai01.getItems().stream().map(UiMetricItem::getDefinitionVersion).toList(),
                "组内版本按版本号升序");
        assertEquals(5, m.totalCount());
    }

    @Test
    void 按状态过滤只保留对应状态() {
        LibraryTreeModel m = new LibraryTreeModel(dataset());
        List<LibraryTreeModel.Group> pub = m.groupsByStatus(MetricStatus.PUBLISHED);
        assertEquals(2, pub.stream().mapToInt(LibraryTreeModel.Group::getVersionCount).sum());
        assertTrue(pub.stream().flatMap(g -> g.getItems().stream())
                .allMatch(it -> it.getStatus() == MetricStatus.PUBLISHED));
        assertEquals(1, m.groupsByStatus(MetricStatus.DRAFT).stream()
                .mapToInt(LibraryTreeModel.Group::getVersionCount).sum());
        assertEquals(2, m.groupsByStatus(Set.of(MetricStatus.DRAFT, MetricStatus.PENDING_REVIEW))
                .stream().mapToInt(LibraryTreeModel.Group::getVersionCount).sum());
    }

    @Test
    void 关键字搜索命中编码标题版本且无匹配为空() {
        LibraryTreeModel m = new LibraryTreeModel(dataset());
        assertEquals(1, m.search("院感").size(), "标题命中 HAI-01 三条 → 一组");
        assertEquals(2, m.search("HAI").size(), "编码命中 HAI-01 / HAI-02 → 两组");
        assertEquals(1, m.search("v3").size(), "版本号命中");
        assertTrue(m.search("不存在的口径").isEmpty(), "无匹配返回空分组");
    }

    @Test
    void 组合过滤与搜索生效且空库有明确引导() {
        LibraryTreeModel m = new LibraryTreeModel(dataset());
        m.setStatusFilter(MetricStatus.PUBLISHED);
        m.setKeyword("HAI-02");
        List<LibraryTreeModel.Group> v = m.visibleGroups();
        assertEquals(1, v.size());
        assertEquals("HAI-02", v.get(0).getCode());
        assertEquals(1, m.visibleCount());
        m.setKeyword("不存在");
        assertTrue(m.visibleGroups().isEmpty(), "过滤 + 搜索无命中");
        assertTrue(m.noMatchHint().contains("调整"));

        LibraryTreeModel empty = new LibraryTreeModel(List.of());
        assertTrue(empty.isEmpty());
        assertTrue(empty.emptyHint().contains("导入") || empty.emptyHint().contains("新建"));
        assertTrue(empty.loadingHint().contains("加载"));
    }

    @Test
    void 状态徽标文本与语义色五态可区分() {
        assertEquals("DRAFT", LibraryTreeModel.statusBadgeText(MetricStatus.DRAFT));
        assertEquals("PUBLISHED", LibraryTreeModel.statusBadgeText(MetricStatus.PUBLISHED));
        Set<Integer> colors = new HashSet<>();
        for (MetricStatus s : MetricStatus.values()) {
            colors.add(LibraryTreeModel.statusBadgeColor(s));
        }
        assertEquals(5, colors.size(), "五种状态徽标颜色互不相同");
        assertEquals(UiTokens.SUCCESS, LibraryTreeModel.statusBadgeColor(MetricStatus.PUBLISHED));
        assertEquals(UiTokens.DANGER, LibraryTreeModel.statusBadgeColor(MetricStatus.REJECTED));
    }

    @Test
    void 详情面板字段齐全且值与选中口径一致() {
        DetailPanelModel d = new DetailPanelModel();
        assertFalse(d.hasSelection());
        assertTrue(d.emptyHint().contains("选择"));
        assertTrue(d.lines().isEmpty());

        d.select(item("HAI-01", "v3", "院感发病率", MetricStatus.PUBLISHED));
        assertTrue(d.hasSelection());
        assertTrue(d.linesComplete(), "十二项字段齐全且非空");
        List<String> labels = d.lines().stream()
                .map(DetailPanelModel.DetailLine::getLabel).toList();
        assertEquals(DetailPanelModel.requiredLabels(), labels, "字段顺序与约定一致");
        assertEquals(12, labels.size());

        Map<String, String> byLabel = new LinkedHashMap<>();
        for (DetailPanelModel.DetailLine line : d.lines()) {
            byLabel.put(line.getLabel(), line.getValue());
        }
        assertEquals("院感发病率", byLabel.get("标题"));
        assertEquals("2026-01-01 ~ 2026-06-30", byLabel.get("生效期"));
        assertEquals("主任B", byLabel.get("审批人"));
        assertEquals("PUBLISHED", byLabel.get("状态"));
        assertEquals("0301、0302", byLabel.get("适用科室"));
        assertTrue(d.loadingHint().contains("加载"));
    }

    @Test
    void 三栏布局之和恰等于容器宽且折叠生效() {
        ThreeColumnLayout.ColumnSpec spec = ThreeColumnLayout.compute(1280);
        assertTrue(spec.getCenter() > spec.getRight(), "中间详情栏最宽");
        assertTrue(spec.getRight() > spec.getLeft(), "右侧差异栏宽于左侧库树");
        assertEquals(1280, spec.total(), "三栏 + 两条分隔条恰好等于容器宽");
        assertTrue(spec.fits(), "无溢出");
        assertEquals(1280 - 2 * ThreeColumnLayout.DIVIDER,
                spec.getLeft() + spec.getCenter() + spec.getRight());

        ThreeColumnLayout.ColumnSpec folded = ThreeColumnLayout.compute(1280, false, true);
        assertEquals(ThreeColumnLayout.COLLAPSED_HANDLE, folded.getRight(), "右栏折叠为把手");
        assertTrue(folded.getCenter() > spec.getCenter(), "右栏折叠后中栏吸收剩余空间");
        assertTrue(folded.fits());

        ThreeColumnLayout.ColumnSpec small = ThreeColumnLayout.compute(768);
        assertTrue(small.fits());
        assertEquals(768, small.total());
        assertThrows(AppException.class, () -> ThreeColumnLayout.compute(0),
                "过小容器宽度显式报错");
    }
}
