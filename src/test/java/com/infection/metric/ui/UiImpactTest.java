package com.infection.metric.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务52（M8-C）：依赖树层级与边依据、深度控制、超预算提示、空态、导出列。 */
class UiImpactTest {

    private static DependencyTreeModel chain() {
        return new DependencyTreeModel()
                .addEdge("ROOT", "A", "FP-1", "A 复用 ROOT 分母", 1)
                .addEdge("A", "B", "FP-2", "B 复用 A 分母", 2)
                .addEdge("B", "C", "FP-3", "C 复用 B 分母", 3);
    }

    private static ImpactRow row(String code, String name, String quarter, String note) {
        return new ImpactRow(code, name, "v2", List.of(quarter), note,
                "变更条目#12 / 口径包 v2026.1", 1);
    }

    @Test
    void 依赖树按层级展示且每条边带指纹与人话依据() {
        DependencyTreeModel m = chain();
        assertEquals(3, m.allEdges().size());
        assertTrue(m.allEdgesHaveBasis(), "每条边都带指纹与依据");
        assertEquals(2, m.edgesUpToDepth(2).size(), "深度≤2 的边可见");
        assertEquals(3, m.levels().size(), "1/2/3 三个层级");

        List<String> lines = m.renderLines(3);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("指纹") && lines.get(0).contains("依据"));
        assertTrue(lines.get(2).contains("第3层"), "第三层带深度标注");
    }

    @Test
    void 缺人话依据的边被显式拒绝() {
        DependencyTreeModel m = new DependencyTreeModel();
        AppException ex = assertThrows(AppException.class,
                () -> m.addEdge("ROOT", "A", "FP-1", "", 1));
        assertEquals(ErrorCodes.E_EDGE_001, ex.getCode());
        assertTrue(m.isEmpty(), "被拒的边没有入树");
        assertThrows(AppException.class,
                () -> m.addEdge("ROOT", "A", "", "有依据但缺指纹", 1));
    }

    @Test
    void 深度默认直接依赖且可显式跨层并显示深度标注() {
        DepthControl c = new DepthControl();
        assertEquals(1, c.currentDepth());
        assertEquals(1, c.defaultDepth());
        assertEquals("直接依赖", c.depthLabel(1));
        assertEquals("第 3 层依赖", c.depthLabel(3));
        assertEquals(3, c.requestDepth(3), "显式请求第 3 层");
        assertEquals(3, c.currentDepth());
        c.reset();
        assertEquals(1, c.currentDepth(), "重置回到默认直接依赖");
    }

    @Test
    void 非法深度抛E_GRAPH_006并给合法范围且不改当前深度() {
        DepthControl c = new DepthControl();
        AppException zero = assertThrows(AppException.class, () -> c.requestDepth(0));
        assertEquals(ErrorCodes.E_GRAPH_006, zero.getCode());
        assertEquals("0", zero.getContext().get("depth"));
        assertTrue(zero.getContext().get("合法范围").contains("1"));
        assertTrue(zero.getContext().get("合法范围").contains("5"));
        assertEquals(ErrorCodes.E_GRAPH_006,
                assertThrows(AppException.class, () -> c.requestDepth(6)).getCode());
        assertEquals(1, c.currentDepth(), "非法请求不改变当前深度");
    }

    @Test
    void 超预算给明确提示而不卡死() {
        DepthControl c = new DepthControl();
        DepthControl.BudgetHint ok = c.checkBudget(10);
        assertFalse(ok.isExceeded());
        assertTrue(ok.getMessage().contains("10"));

        DepthControl.BudgetHint over = assertDoesNotThrow(() -> c.checkBudget(600),
                "超预算不抛异常、不卡死");
        assertTrue(over.isExceeded());
        assertTrue(over.getMessage().contains("600"), "提示带实际规模");
        assertTrue(over.getMessage().contains("500"), "提示带预算上限");
        assertEquals(600, over.getActual());
        assertEquals(DepthControl.MAX_NODES, over.getLimit());
    }

    @Test
    void 导出列齐全且空态结构完整() {
        ImpactTableModel m = new ImpactTableModel(List.of(
                row("HAI-02", "手术部位感染率", "2026-Q1", "分子新增排除条件：切口等级不等于Ⅲ"),
                row("HAI-03", "导管相关感染率", "2026-Q2", "分母口径由出院改为住院")));
        assertEquals(7, m.columns().size());
        assertTrue(m.columns().containsAll(List.of(
                "受影响指标编码", "指标名称", "需重算季度", "人话说明", "来源引用")));

        String[] csvLines = m.exportCsv().split("\n", -1);
        assertEquals(3, csvLines.length, "表头 + 2 行数据");
        assertTrue(csvLines[0].contains("受影响指标编码"));

        JsonNode root = JsonSupport.parse(m.exportJson());
        assertEquals(2, root.path("impacted_indicators").size());
        assertTrue(root.path("impacted_indicators").get(0).has("来源引用"));
        assertTrue(root.path("impacted_indicators").get(0).path("需重算季度").isArray());

        ImpactTableModel empty = new ImpactTableModel(List.of());
        assertTrue(empty.isEmpty());
        assertEquals("该口径无下游依赖，无需重算", empty.emptyHint());
        assertEquals(1, empty.exportCsv().split("\n", -1).length, "空表仍导出完整表头");
        assertTrue(JsonSupport.parse(empty.exportJson()).path("说明").asText().contains("无下游依赖"));
    }

    @Test
    void JSON与CSV导出内容一致() {
        ImpactTableModel m = new ImpactTableModel(List.of(
                row("HAI-02", "手术部位感染率", "2026-Q1", "分子新增排除条件：切口等级不等于Ⅲ")));
        String csv = m.exportCsv();
        String json = m.exportJson();
        assertTrue(csv.contains("HAI-02") && json.contains("HAI-02"), "两套导出都含受影响指标编码");
        assertTrue(csv.contains("2026-Q1") && json.contains("2026-Q1"), "都含需重算季度");
        assertTrue(csv.contains("分子新增排除条件") && json.contains("分子新增排除条件"),
                "都含人话变更说明");
        assertEquals(1, JsonSupport.parse(json).path("受影响指标数").asInt());
    }
}
