package com.infection.metric.metriclib;

import com.infection.metric.common.AppException;
import com.infection.metric.expr.ConditionTree;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 任务2：口径条目模型（联合主键 / 内容指纹 / 往返序列化）与状态机矩阵。 */
class MetricDefinitionTest {

    private static MetricDefinition sample() {
        return MetricDefinition.builder("HAI-01", "v1")
                .title("院感报告例数")
                .numerator(ConditionTree.and(
                        ConditionTree.leaf("dept_code", "in", List.of("0301", "0302")),
                        ConditionTree.leaf("mdr_flag", "eq", true)))
                .denominator(ConditionTree.leaf("discharge_date", "between",
                        List.of("2026-01-01", "2026-03-31")))
                .addExclusion(ConditionTree.leaf("infection_site", "ne", "其他部位"))
                .deptCodes(List.of("0301", "0302"))
                .periods(List.of("2026-Q1"))
                .addDataSource(new MetricDefinition.DataSourceRef("hai", "infection_report", "infection_site"))
                .effectiveFrom("2026-01-01")
                .effectiveTo("2026-03-31")
                .status(MetricStatus.PENDING_REVIEW)
                .author("专员")
                .dictionaryVersion("v2")
                .build();
    }

    @Test
    void 联合主键与内容指纹稳定且状态不参与内容比较() {
        MetricDefinition d = sample();
        assertEquals("HAI-01|v1", d.key());
        assertEquals(64, d.contentFingerprint().length());
        assertEquals(d.contentFingerprint(), sample().contentFingerprint());

        MetricDefinition sameContentOtherStatus = d.toBuilder().status(MetricStatus.PUBLISHED).build();
        assertTrue(d.sameContent(sameContentOtherStatus));

        MetricDefinition changed = d.toBuilder().title("改过的标题").build();
        assertFalse(d.sameContent(changed));
        assertNotEquals(d.contentFingerprint(), changed.contentFingerprint());
        assertTrue(d.declaredFields().contains("dept_code"));
        assertTrue(d.declaredFields().contains("discharge_date"));
    }

    @Test
    void toJson与fromJson往返保持全部业务字段() {
        MetricDefinition d = sample();
        String json = d.toJson().toString();
        MetricDefinition back = MetricDefinition.fromJson(json);

        assertEquals(d.key(), back.key());
        assertEquals("院感报告例数", back.getTitle());
        assertEquals(MetricStatus.PENDING_REVIEW, back.getStatus());
        assertEquals(List.of("0301", "0302"), back.getDeptCodes());
        assertEquals(List.of("2026-Q1"), back.getPeriods());
        assertEquals(1, back.getExclusions().size());
        assertEquals(1, back.getDataSources().size());
        assertEquals("infection_site", back.getDataSources().get(0).getField());
        assertEquals("2026-01-01", back.getEffectiveFrom());
        assertEquals("v2", back.getDictionaryVersion());
        assertTrue(d.sameContent(back));
    }

    @Test
    void 状态机全部合法跃迁可达() {
        assertTrue(MetricStatus.DRAFT.canTransitionTo(MetricStatus.PENDING_REVIEW));
        assertTrue(MetricStatus.PENDING_REVIEW.canTransitionTo(MetricStatus.PUBLISHED));
        assertTrue(MetricStatus.PENDING_REVIEW.canTransitionTo(MetricStatus.REJECTED));
        assertTrue(MetricStatus.REJECTED.canTransitionTo(MetricStatus.DRAFT));
        assertTrue(MetricStatus.PUBLISHED.canTransitionTo(MetricStatus.RETIRED));

        assertDoesNotThrow(() -> MetricStatus.DRAFT.requireTransition(MetricStatus.PENDING_REVIEW));
        assertDoesNotThrow(() -> MetricStatus.PENDING_REVIEW.requireTransition(MetricStatus.PUBLISHED));
        assertDoesNotThrow(() -> MetricStatus.REJECTED.requireTransition(MetricStatus.DRAFT));
        assertEquals(Set.of(MetricStatus.PENDING_REVIEW), MetricStatus.DRAFT.allowedTargets());
        assertEquals(Set.of(MetricStatus.PUBLISHED, MetricStatus.REJECTED),
                MetricStatus.PENDING_REVIEW.allowedTargets());
        assertEquals(Set.of(), MetricStatus.RETIRED.allowedTargets());
    }

    @Test
    void 状态机全部非法跃迁被拒含跳过复核的DRAFT到PUBLISHED() {
        assertFalse(MetricStatus.DRAFT.canTransitionTo(MetricStatus.PUBLISHED));
        assertFalse(MetricStatus.DRAFT.canTransitionTo(MetricStatus.REJECTED));
        assertFalse(MetricStatus.DRAFT.canTransitionTo(MetricStatus.RETIRED));
        assertFalse(MetricStatus.DRAFT.canTransitionTo(MetricStatus.DRAFT));
        assertFalse(MetricStatus.PENDING_REVIEW.canTransitionTo(MetricStatus.DRAFT));
        assertFalse(MetricStatus.PENDING_REVIEW.canTransitionTo(MetricStatus.RETIRED));
        assertFalse(MetricStatus.PUBLISHED.canTransitionTo(MetricStatus.DRAFT));
        assertFalse(MetricStatus.PUBLISHED.canTransitionTo(MetricStatus.PUBLISHED));
        assertFalse(MetricStatus.REJECTED.canTransitionTo(MetricStatus.PUBLISHED));
        assertFalse(MetricStatus.RETIRED.canTransitionTo(MetricStatus.DRAFT));
        assertFalse(MetricStatus.DRAFT.canTransitionTo(null));

        AppException skip = assertThrows(AppException.class,
                () -> MetricStatus.DRAFT.requireTransition(MetricStatus.PUBLISHED));
        assertEquals("E_STATE_001", skip.getCode());
        assertTrue(skip.render().contains("DRAFT"));
        assertTrue(skip.render().contains("PUBLISHED"));
    }

    @Test
    void of解析大小写与未知状态显式报错() {
        assertEquals(MetricStatus.PUBLISHED, MetricStatus.of("published"));
        assertEquals(MetricStatus.PENDING_REVIEW, MetricStatus.of(" PENDING_REVIEW "));
        assertEquals(MetricStatus.DRAFT, MetricStatus.of(null));

        AppException ex = assertThrows(AppException.class, () -> MetricStatus.of("NOT_A_STATUS"));
        assertEquals("E_STATE_001", ex.getCode());
        assertTrue(ex.render().contains("NOT_A_STATUS"));
    }
}
