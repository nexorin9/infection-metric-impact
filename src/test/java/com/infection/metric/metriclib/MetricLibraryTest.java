package com.infection.metric.metriclib;

import com.infection.metric.common.AppException;
import com.infection.metric.expr.ConditionTree;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务2：内存口径库的多版本并存、主键冲突、状态机与草稿隔离。 */
class MetricLibraryTest {

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

    @Test
    void 同一指标多版本并存且按版本检索() {
        MetricLibrary lib = new MetricLibrary();
        lib.put(def("HAI-01", "v1", "一版口径"));
        lib.put(def("HAI-01", "v2", "二版口径"));
        lib.put(def("HAI-02", "v1", "另一指标"));

        assertEquals(3, lib.all().size());
        assertEquals(2, lib.versions("HAI-01").size());
        assertEquals("v1", lib.versions("HAI-01").get(0).getDefinitionVersion());
        assertEquals("二版口径", lib.get("HAI-01", "v2").getTitle());
        assertNull(lib.get("HAI-01", "v9"));
    }

    @Test
    void 主键重复内容不同报冲突且不静默覆盖内容相同幂等() {
        MetricLibrary lib = new MetricLibrary();
        lib.put(def("HAI-01", "v1", "原始口径"));

        AppException ex = assertThrows(AppException.class,
                () -> lib.put(def("HAI-01", "v1", "被改坏的口径")));
        assertEquals("E_CONFLICT_001", ex.getCode());
        assertTrue(ex.render().contains("HAI-01|v1"));
        assertTrue(ex.render().contains("原始口径"));
        assertEquals("原始口径", lib.get("HAI-01", "v1").getTitle());

        assertDoesNotThrow(() -> lib.put(def("HAI-01", "v1", "原始口径")));
        assertEquals(1, lib.all().size());
        assertEquals("原始口径", lib.get("HAI-01", "v1").getTitle());
    }

    @Test
    void 状态机合法跃迁全部可达且发布带审批留痕() {
        MetricLibrary lib = new MetricLibrary();
        lib.put(def("HAI-01", "v1", "口径"));

        assertEquals(MetricStatus.DRAFT, lib.get("HAI-01", "v1").getStatus());
        lib.transition("HAI-01", "v1", MetricStatus.PENDING_REVIEW, "专员");
        assertEquals(MetricStatus.PENDING_REVIEW, lib.get("HAI-01", "v1").getStatus());

        MetricDefinition published = lib.transition("HAI-01", "v1", MetricStatus.PUBLISHED,
                "主任", "主任", "2026-01-05T09:30:00Z");
        assertEquals(MetricStatus.PUBLISHED, published.getStatus());
        assertEquals("主任", published.getApprover());
        assertEquals("2026-01-05T09:30:00Z", published.getApprovedAt());
        assertNotNull(lib.findPublished("HAI-01"));

        lib.transition("HAI-01", "v1", MetricStatus.RETIRED, "主任");
        assertEquals(MetricStatus.RETIRED, lib.get("HAI-01", "v1").getStatus());

        // REJECTED → DRAFT 这条回路单独再走一遍
        MetricLibrary loop = new MetricLibrary();
        loop.put(def("HAI-02", "v1", "口径"));
        loop.transition("HAI-02", "v1", MetricStatus.PENDING_REVIEW, "专员");
        loop.transition("HAI-02", "v1", MetricStatus.REJECTED, "主任");
        assertEquals(MetricStatus.REJECTED, loop.get("HAI-02", "v1").getStatus());
        loop.transition("HAI-02", "v1", MetricStatus.DRAFT, "专员");
        assertEquals(MetricStatus.DRAFT, loop.get("HAI-02", "v1").getStatus());
    }

    @Test
    void 非法跃迁报错且状态与审批字段均不变() {
        MetricLibrary lib = new MetricLibrary();
        lib.put(def("HAI-01", "v1", "口径"));

        AppException skip = assertThrows(AppException.class,
                () -> lib.transition("HAI-01", "v1", MetricStatus.PUBLISHED, "主任"));
        assertEquals("E_STATE_001", skip.getCode());
        assertEquals(MetricStatus.DRAFT, lib.get("HAI-01", "v1").getStatus());
        assertEquals("", lib.get("HAI-01", "v1").getApprover());

        lib.transition("HAI-01", "v1", MetricStatus.PENDING_REVIEW, "专员");
        AppException backward = assertThrows(AppException.class,
                () -> lib.transition("HAI-01", "v1", MetricStatus.DRAFT, "专员"));
        assertEquals("E_STATE_001", backward.getCode());
        assertEquals(MetricStatus.PENDING_REVIEW, lib.get("HAI-01", "v1").getStatus());
    }

    @Test
    void 发布缺审批人被拒且状态不推进() {
        MetricLibrary lib = new MetricLibrary();
        lib.put(def("HAI-01", "v1", "口径"));
        lib.transition("HAI-01", "v1", MetricStatus.PENDING_REVIEW, "专员");

        AppException ex = assertThrows(AppException.class,
                () -> lib.transition("HAI-01", "v1", MetricStatus.PUBLISHED, ""));
        assertEquals("E_AUDIT_001", ex.getCode());
        assertEquals(MetricStatus.PENDING_REVIEW, lib.get("HAI-01", "v1").getStatus());
        assertEquals("", lib.get("HAI-01", "v1").getApprover());
    }

    @Test
    void 草稿改动不影响已发布版本任何字段且已发布版本不可直接改内容() {
        MetricLibrary lib = new MetricLibrary();
        MetricDefinition published = def("HAI-01", "v1", "已发布口径").toBuilder()
                .status(MetricStatus.PUBLISHED)
                .approver("主任")
                .approvedAt("2026-01-05T09:30:00Z")
                .build();
        lib.put(published);

        MetricDefinition draft = lib.createDraft("HAI-01", "v1", "v2");
        assertEquals(MetricStatus.DRAFT, draft.getStatus());
        assertEquals("已发布口径", draft.getTitle());
        assertEquals("", draft.getApprover());
        assertEquals("", draft.getApprovedAt());
        assertEquals(2, lib.versions("HAI-01").size());

        lib.updateContent(draft.toBuilder().title("草稿改过的标题").build());
        assertEquals("草稿改过的标题", lib.get("HAI-01", "v2").getTitle());

        MetricDefinition stillPublished = lib.get("HAI-01", "v1");
        assertEquals("已发布口径", stillPublished.getTitle());
        assertEquals(MetricStatus.PUBLISHED, stillPublished.getStatus());
        assertEquals("主任", stillPublished.getApprover());
        assertEquals("2026-01-05T09:30:00Z", stillPublished.getApprovedAt());
        assertEquals(published.getNumerator().canonicalJson(), stillPublished.getNumerator().canonicalJson());
        assertEquals("已发布口径", lib.findPublished("HAI-01").getTitle());

        AppException ex = assertThrows(AppException.class,
                () -> lib.updateContent(stillPublished.toBuilder().title("想偷偷改已发布口径").build()));
        assertEquals("E_STATE_001", ex.getCode());
        assertEquals("已发布口径", lib.get("HAI-01", "v1").getTitle());
    }
}
