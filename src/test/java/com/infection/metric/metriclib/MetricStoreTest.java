package com.infection.metric.metriclib;

import com.infection.metric.common.AppException;
import com.infection.metric.common.AtomicFiles;
import com.infection.metric.expr.ConditionTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 任务17：文件型口径库的原子写、快照、幂等、冲突与年度分区。 */
class MetricStoreTest {

    @TempDir Path root;

    /** 全局注入点必须复位，避免影响同一次 -Dtest='Metric*Test' 里的其它测试类。 */
    @AfterEach
    void resetInjection() {
        AtomicFiles.failAfterTemp = null;
    }

    private static MetricDefinition def(String code, String version, String title) {
        return MetricDefinition.builder(code, version)
                .title(title)
                .numerator(ConditionTree.leaf("dept_code", "eq", "0301"))
                .denominator(ConditionTree.leaf("discharge_date", "between",
                        List.of("2026-01-01", "2026-03-31")))
                .deptCodes(List.of("0301"))
                .periods(List.of("2026-Q1"))
                .effectiveFrom("2026-01-01")
                .author("院感科专员")
                .build();
    }

    private static List<String> snapshotNames(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("snapshot-") && n.endsWith(".json"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static long countFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }

    @Test
    void 原子写中途失败旧文件仍可读且不留半截JSON() throws IOException {
        MetricStore store = new MetricStore(root);
        store.save(def("HAI-01", "v1", "标题一"));

        Path defDir = root.resolve("2026").resolve("HAI-01");
        String before = AtomicFiles.readString(defDir.resolve("v1.json"));
        assertTrue(before.contains("标题一"));
        assertTrue(Files.exists(root.resolve("index.json")));

        AtomicFiles.failAfterTemp = () -> { throw new IllegalStateException("模拟写入中途失败"); };
        try {
            assertThrows(RuntimeException.class, () -> store.save(def("HAI-01", "v2", "标题二")));
        } finally {
            AtomicFiles.failAfterTemp = null;
        }

        assertEquals("标题一", store.load("HAI-01", "v1").getTitle());
        assertEquals(before, AtomicFiles.readString(defDir.resolve("v1.json")));
        assertFalse(Files.exists(defDir.resolve("v2.json")), "失败的目标文件不得留下半截 JSON");
        assertTrue(store.index().path("years").path("2026").toString().contains("v1"));
        assertFalse(store.index().path("years").path("2026").toString().contains("\"v2\""),
                "写入失败不得污染索引");
    }

    @Test
    void 重复写相同内容幂等不产生第二份文件与第二条索引记录() throws IOException {
        MetricStore store = new MetricStore(root);
        store.save(def("HAI-01", "v1", "标题一"));
        Path defDir = root.resolve("2026").resolve("HAI-01");
        long before = countFiles(defDir);

        assertDoesNotThrow(() -> store.save(def("HAI-01", "v1", "标题一")));

        assertEquals(before, countFiles(defDir));
        assertEquals(1, store.index().path("years").path("2026").size());
        assertEquals("标题一", store.load("HAI-01", "v1").getTitle());
    }

    @Test
    void 同主键内容不同报冲突且磁盘内容不被覆盖() {
        MetricStore store = new MetricStore(root);
        store.save(def("HAI-01", "v1", "标题一"));

        AppException ex = assertThrows(AppException.class,
                () -> store.save(def("HAI-01", "v1", "被改坏的标题")));
        assertEquals("E_CONFLICT_001", ex.getCode());
        assertTrue(ex.render().contains("HAI-01|v1"));
        assertEquals("标题一", store.load("HAI-01", "v1").getTitle());
        assertEquals(1, store.index().path("years").path("2026").size());
    }

    @Test
    void 快照保留最近N份并按时间淘汰且当前内容正确() throws IOException {
        MetricStore store = new MetricStore(root, 3);
        store.save(def("HAI-01", "v1", "第0版"));
        Path defDir = root.resolve("2026").resolve("HAI-01");
        assertTrue(snapshotNames(defDir).isEmpty(), "首次新增不产生变更前快照");

        store.updateContent(def("HAI-01", "v1", "第1版"));
        store.updateContent(def("HAI-01", "v1", "第2版"));
        store.updateContent(def("HAI-01", "v1", "第3版"));
        List<String> three = snapshotNames(defDir);
        assertEquals(3, three.size());
        assertTrue(three.stream().allMatch(n -> n.startsWith("snapshot-")));
        assertTrue(three.stream().allMatch(n -> n.matches(".*\\d{4}-\\d{2}-\\d{2}T.*")),
                "快照名必须含 ISO 时间戳");

        String oldest = three.get(0);
        store.updateContent(def("HAI-01", "v1", "第4版"));
        List<String> afterPrune = snapshotNames(defDir);
        assertEquals(3, afterPrune.size(), "超出上限后仍只保留最近 3 份");
        assertFalse(afterPrune.contains(oldest), "最旧的快照应被淘汰");
        assertTrue(afterPrune.get(2).compareTo(oldest) > 0);

        assertEquals("第4版", store.load("HAI-01", "v1").getTitle());
        assertTrue(AtomicFiles.readString(defDir.resolve(afterPrune.get(0))).contains("indicator_code"));
    }

    @Test
    void index损坏或缺失显式报E_STORE_001不静默返回空库() throws IOException {
        MetricStore store = new MetricStore(root);
        store.save(def("HAI-01", "v1", "标题一"));
        Path idx = root.resolve("index.json");

        Files.writeString(idx, "{ 这不是合法 JSON");
        assertEquals("E_STORE_001",
                assertThrows(AppException.class, () -> store.load("HAI-01", "v1")).getCode());
        assertEquals("E_STORE_001",
                assertThrows(AppException.class, store::listYears).getCode());
        assertEquals("E_STORE_001",
                assertThrows(AppException.class, store::index).getCode());

        Files.delete(idx);
        assertEquals("E_STORE_001",
                assertThrows(AppException.class, () -> store.load("HAI-01", "v1")).getCode());

        Files.writeString(idx, "{\"schema_version\":1}");
        assertEquals("E_STORE_001",
                assertThrows(AppException.class, store::listYears).getCode(),
                "结构损坏（缺 years）同样显式失败");
    }

    @Test
    void 按年度分区落盘并可单独列出() {
        MetricStore store = new MetricStore(root);
        store.save(def("HAI-01", "v1", "2025口径").toBuilder().effectiveFrom("2025-01-01").build());
        store.save(def("HAI-01", "v2", "2026口径一"));
        store.save(def("HAI-02", "v1", "2026口径二"));

        assertEquals(List.of("2025", "2026"), store.listYears());
        assertEquals(2, store.index().path("years").size());
        assertTrue(Files.exists(root.resolve("2025").resolve("HAI-01").resolve("v1.json")));
        assertTrue(Files.exists(root.resolve("2026").resolve("HAI-01").resolve("v2.json")));

        assertEquals(1, store.listByYear("2025").size());
        assertEquals("2025口径", store.listByYear("2025").get(0).getTitle());
        assertEquals(2, store.listByYear("2026").size());
        assertEquals("HAI-01|v2", store.listByYear("2026").get(0).key());
        assertEquals("2026口径二", store.listByYear("2026").get(1).getTitle());
    }

    @Test
    void 状态跃迁落盘前留快照且非法跃迁状态不变() throws IOException {
        MetricStore store = new MetricStore(root);
        store.save(def("HAI-01", "v1", "口径"));

        AppException skip = assertThrows(AppException.class,
                () -> store.transition("HAI-01", "v1", MetricStatus.PUBLISHED, "主任"));
        assertEquals("E_STATE_001", skip.getCode());
        assertEquals(MetricStatus.DRAFT, store.load("HAI-01", "v1").getStatus());

        store.transition("HAI-01", "v1", MetricStatus.PENDING_REVIEW, "专员");
        assertEquals(MetricStatus.PENDING_REVIEW, store.load("HAI-01", "v1").getStatus());
        MetricDefinition published = store.transition("HAI-01", "v1", MetricStatus.PUBLISHED, "主任");
        assertEquals(MetricStatus.PUBLISHED, published.getStatus());
        assertEquals("主任", published.getApprover());
        assertFalse(snapshotNames(root.resolve("2026").resolve("HAI-01")).isEmpty(),
                "每次状态跃迁前都应留一份快照");
    }
}
