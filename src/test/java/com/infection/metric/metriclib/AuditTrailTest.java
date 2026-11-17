package com.infection.metric.metriclib;

import com.infection.metric.common.AppException;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.expr.ConditionTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务18：字段级 diff、只追加、幂等、变更链反查与审批留痕。 */
class AuditTrailTest {

    @TempDir Path dir;

    private Path auditFile() { return dir.resolve("audit.jsonl"); }

    private static MetricDefinition def(String code, String version, String title) {
        return MetricDefinition.builder(code, version)
                .title(title)
                .numerator(ConditionTree.leaf("dept_code", "eq", "0301"))
                .denominator(ConditionTree.leaf("discharge_date", "between",
                        List.of("2026-01-01", "2026-03-31")))
                .deptCodes(List.of("0301"))
                .effectiveFrom("2026-01-01")
                .status(MetricStatus.DRAFT)
                .author("专员")
                .build();
    }

    @Test
    void 字段级diff只记录真正变化的字段() {
        AuditTrail trail = new AuditTrail(auditFile());
        MetricDefinition before = def("HAI-01", "v1", "原始口径");
        MetricDefinition after = before.toBuilder().title("改过的标题").build();

        AuditRecord rec = trail.recordChange("HAI-01", "v1", "专员", before, after);

        assertEquals(1, rec.getFieldDiff().size(), "只有 title 变化，其余字段不得落审计");
        AuditRecord.FieldDiff d = rec.getFieldDiff().get(0);
        assertEquals("title", d.getField());
        assertTrue(d.getBefore().contains("原始口径"));
        assertTrue(d.getAfter().contains("改过的标题"));
        assertTrue(rec.getFieldDiff().stream().noneMatch(x -> "status".equals(x.getField())));
        assertTrue(rec.getFieldDiff().stream().noneMatch(x -> "numerator".equals(x.getField())));

        AuditRecord multi = trail.recordChange("HAI-01", "v1", "专员",
                before, before.toBuilder().title("再改一次").status(MetricStatus.PENDING_REVIEW).build());
        assertEquals(2, multi.getFieldDiff().size(), "title 与 status 两个变化都应记录");
        assertTrue(multi.getFieldDiff().stream().anyMatch(x -> "status".equals(x.getField())));
    }

    @Test
    void 审计表只追加不提供改写删除接口且旧内容不被改写() throws IOException {
        AuditTrail trail = new AuditTrail(auditFile());
        trail.submitReview("HAI-01", "v1", "专员");
        String first = Files.readString(auditFile());
        assertTrue(first.contains("SUBMIT_REVIEW"));

        trail.approve("HAI-01", "v1", "专员", "主任", "同意送审");
        String second = Files.readString(auditFile());

        assertTrue(second.startsWith(first), "只追加：已有记录必须原样保留在文件前缀");
        assertTrue(second.length() > first.length());
        assertEquals(2, trail.chain("HAI-01").size());
        assertEquals(AuditTrail.ACTION_SUBMIT_REVIEW, trail.chain("HAI-01").get(0).getAction());

        for (Method m : AuditTrail.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            assertFalse(n.contains("delete") || n.contains("remove") || n.contains("update")
                            || n.contains("clear") || n.contains("rewrite") || n.contains("erase"),
                    "审计表不得暴露改写/删除接口：" + m.getName());
        }
    }

    @Test
    void 反查链完整按时间排序且支持版本过滤() {
        AuditTrail trail = new AuditTrail(auditFile());
        trail.submitReview("HAI-01", "v1", "专员");
        trail.approve("HAI-01", "v1", "专员", "主任", "同意");
        trail.recordChange("HAI-01", "v2", "专员",
                def("HAI-01", "v2", "旧"), def("HAI-01", "v2", "新"));
        trail.submitReview("HAI-02", "v1", "另一专员");

        List<AuditRecord> chain = trail.chain("HAI-01");
        assertEquals(3, chain.size(), "只反查该 indicator_code 的完整变更链");
        assertTrue(chain.stream().allMatch(r -> "HAI-01".equals(r.getIndicatorCode())));
        assertEquals(AuditTrail.ACTION_SUBMIT_REVIEW, chain.get(0).getAction());
        assertEquals(AuditTrail.ACTION_APPROVE, chain.get(1).getAction());
        assertTrue(chain.get(0).getTimestamp().compareTo(chain.get(1).getTimestamp()) <= 0);

        List<AuditRecord> v2 = trail.chain("HAI-01", "v2");
        assertEquals(1, v2.size());
        assertEquals(AuditTrail.ACTION_CHANGE, v2.get(0).getAction());
        assertEquals(1, trail.chain("HAI-02").size());
    }

    @Test
    void 未经approve不得出现PUBLISHED() {
        AuditTrail trail = new AuditTrail(auditFile());
        trail.submitReview("HAI-01", "v1", "专员");

        assertFalse(trail.hasApprovalBeforePublish("HAI-01", "v1"));
        AppException ex = assertThrows(AppException.class,
                () -> trail.recordPublish("HAI-01", "v1", "专员"));
        assertEquals("E_PUBLISH_001", ex.getCode());
        assertTrue(ex.render().contains("未经 approve"));
        assertTrue(trail.chain("HAI-01", "v1").stream()
                .noneMatch(r -> AuditTrail.ACTION_PUBLISH.equals(r.getAction())));
        assertEquals(1, trail.chain("HAI-01", "v1").size());

        trail.approve("HAI-01", "v1", "专员", "主任", "同意发布");
        assertTrue(trail.hasApprovalBeforePublish("HAI-01", "v1"));
        trail.recordPublish("HAI-01", "v1", "专员");

        List<AuditRecord> chain = trail.chain("HAI-01", "v1");
        assertEquals(3, chain.size());
        assertEquals(AuditTrail.ACTION_APPROVE, chain.get(1).getAction());
        assertEquals(AuditTrail.ACTION_PUBLISH, chain.get(2).getAction());
        assertEquals("主任", chain.get(1).getApprover());
        assertEquals("同意发布", chain.get(1).getApprovalNote());
    }

    @Test
    void 重复提交同一动作幂等不产生重复记录() {
        AuditTrail trail = new AuditTrail(auditFile());
        AuditRecord a1 = trail.submitReview("HAI-01", "v1", "专员");
        AuditRecord a2 = trail.submitReview("HAI-01", "v1", "专员");
        assertEquals(a1.getAuditId(), a2.getAuditId());
        assertEquals(1, trail.chain("HAI-01").size());

        trail.approve("HAI-01", "v1", "专员", "主任", "同意");
        trail.approve("HAI-01", "v1", "专员", "主任", "同意");
        assertEquals(2, trail.chain("HAI-01").size());
        assertEquals(64, a1.getAuditId().length(), "audit_id 应为 sha256 十六进制串");

        MetricDefinition before = def("HAI-01", "v1", "旧");
        MetricDefinition after = before.toBuilder().title("新").build();
        trail.recordChange("HAI-01", "v1", "专员", before, after);
        trail.recordChange("HAI-01", "v1", "专员", before, after);
        assertEquals(3, trail.chain("HAI-01").size());
        assertEquals(1, trail.chain("HAI-01").stream()
                .filter(r -> AuditTrail.ACTION_CHANGE.equals(r.getAction())).count());
    }

    @Test
    void 审计记录toJson与fromJson往返保持字段() {
        AuditTrail trail = new AuditTrail(auditFile());
        MetricDefinition before = def("HAI-01", "v1", "旧");
        AuditRecord rec = trail.recordChange("HAI-01", "v1", "专员",
                before, before.toBuilder().title("新").build());

        String json = rec.toJson().toString();
        AuditRecord back = AuditRecord.fromJson(JsonSupport.parse(json));
        assertEquals(rec.getAuditId(), back.getAuditId());
        assertEquals("HAI-01", back.getIndicatorCode());
        assertEquals("v1", back.getDefinitionVersion());
        assertEquals(AuditTrail.ACTION_CHANGE, back.getAction());
        assertEquals(1, back.getFieldDiff().size());
        assertEquals("title", back.getFieldDiff().get(0).getField());
        assertEquals(rec.getTimestamp(), back.getTimestamp());

        AuditRecord approved = trail.approve("HAI-01", "v1", "专员", "主任", "同意");
        assertEquals("主任", approved.getApprover());
        assertEquals("同意", approved.getApprovalNote());
        assertThrows(AppException.class,
                () -> trail.approve("HAI-01", "v1", "专员", "", "缺审批人"));
    }
}
