package com.infection.metric.graph;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务24：依赖边依据留痕、反查、口径变更后待复核与人工确认。 */
class EdgeEvidenceTest {

    @Test
    void 无依据建边被拒E_EDGE_001() {
        EdgeEvidenceStore s = new EdgeEvidenceStore();
        AppException ex = assertThrows(AppException.class,
                () -> s.add(new DependencyEdge("A|v1", "DEN@fp", "fp", "")));
        assertEquals(ErrorCodes.E_EDGE_001, ex.getCode());
        assertEquals(0, s.size());

        assertThrows(AppException.class,
                () -> s.add(new DependencyEdge("A|v1", "DEN@fp", "fp", null)));
        assertEquals(0, s.size());
    }

    @Test
    void 反查结果带依据文本与建边元数据() {
        EdgeEvidenceStore s = new EdgeEvidenceStore();
        DependencyEdge e1 = new DependencyEdge("A|v1", "DEN@fp1", "fp1",
                "复用同期出院患者病历总数分母", "2026-01-01", "张三");
        DependencyEdge e2 = new DependencyEdge("A|v1", "DEN@fp2", "fp2",
                "复用同期住院手术患者病历总数分母", "2026-01-02", "李四");
        DependencyEdge e3 = new DependencyEdge("C|v1", "A|v1", "fp3",
                "C 依赖 A 的口径", "2026-01-03", "王五");
        s.add(e1).add(e2).add(e3);
        assertEquals(3, s.size());

        List<DependencyEdge> fromA = s.edgesFrom("A|v1");
        assertEquals(2, fromA.size());
        assertTrue(fromA.stream().allMatch(DependencyEdge::hasEvidence));
        assertTrue(fromA.stream().anyMatch(e -> e.getEvidence().contains("同期出院患者病历总数")));
        assertTrue(fromA.stream().anyMatch(e -> e.getEvidence().contains("同期住院手术患者病历总数")));
        assertEquals("fp1", fromA.get(0).getFingerprint());
        assertEquals("2026-01-01", fromA.get(0).getCreatedAt());
        assertEquals("张三", fromA.get(0).getCreatedBy());

        List<DependencyEdge> toA = s.edgesTo("A|v1");
        assertEquals(1, toA.size());
        assertEquals("C 依赖 A 的口径", toA.get(0).getEvidence());
    }

    @Test
    void 口径内容变更后对应边标记待复核() {
        EdgeEvidenceStore s = new EdgeEvidenceStore();
        DependencyEdge e1 = new DependencyEdge("A|v1", "DEN@DISCHARGE", "OLD_FP",
                "复用同期出院患者病历总数分母");
        DependencyEdge e2 = new DependencyEdge("B|v1", "DEN@DISCHARGE", "OLD_FP",
                "复用同期出院患者病历总数分母");
        DependencyEdge e3 = new DependencyEdge("C|v1", "DEN@SURGERY", "SURGERY_FP",
                "复用同期出院手术患者病历总数分母");
        s.add(e1).add(e2).add(e3);

        // 被依赖口径 DEN@DISCHARGE 内容变更 → 新指纹 NEW_FP，旧边全部待复核
        List<String> marked = s.markReviewRequired("DEN@DISCHARGE", "NEW_FP");
        assertEquals(2, marked.size());
        assertTrue(s.isReviewRequired(e1.getEdgeId()));
        assertTrue(s.isReviewRequired(e2.getEdgeId()));
        assertFalse(s.isReviewRequired(e3.getEdgeId()), "不同分母口径的边不受影响");

        // 指纹未变则无需复核
        EdgeEvidenceStore s2 = new EdgeEvidenceStore();
        DependencyEdge same = new DependencyEdge("D|v1", "DEN@X", "FP", "复用分母");
        s2.add(same);
        assertTrue(s2.markReviewRequired("DEN@X", "FP").isEmpty());
        assertFalse(s2.isReviewRequired(same.getEdgeId()));
    }

    @Test
    void 待复核边不计入影响面_confirm后重新计入() {
        EdgeEvidenceStore s = new EdgeEvidenceStore();
        DependencyEdge e = new DependencyEdge("A|v1", "DEN@D", "FP_OLD", "复用分母");
        s.add(e);

        assertEquals(1, s.confirmedEdgesFrom("A|v1").size());
        assertEquals(1, s.confirmedEdgesTo("DEN@D").size());

        s.markReviewRequired("DEN@D", "FP_NEW");
        assertTrue(s.isReviewRequired(e.getEdgeId()));
        assertEquals(0, s.confirmedEdgesFrom("A|v1").size(), "待复核边不计入影响面");
        assertEquals(0, s.confirmedEdgesTo("DEN@D").size());
        assertEquals(1, s.edgesFrom("A|v1").size(), "反查仍可见该边（仅不计入影响面）");

        s.confirm(e.getEdgeId());
        assertFalse(s.isReviewRequired(e.getEdgeId()));
        assertEquals(1, s.confirmedEdgesFrom("A|v1").size(), "人工确认后重新计入");
        assertEquals(1, s.confirmedEdgesTo("DEN@D").size());
    }

    @Test
    void 边库可JSON序列化往返() {
        EdgeEvidenceStore s = new EdgeEvidenceStore();
        DependencyEdge e = new DependencyEdge("A|v1", "DEN@D", "FP",
                "复用同期出院患者病历总数分母", "2026-01-01", "张三");
        e.markReviewRequired();
        s.add(e);

        ObjectNode json = s.toJson();
        EdgeEvidenceStore restored = EdgeEvidenceStore.fromJson(json);
        assertEquals(1, restored.size());
        DependencyEdge back = restored.byId(e.getEdgeId());
        assertNotNull(back);
        assertEquals("复用同期出院患者病历总数分母", back.getEvidence());
        assertTrue(back.isReviewRequired(), "复核标记随序列化保留");
    }
}
