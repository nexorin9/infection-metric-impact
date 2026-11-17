package com.infection.metric.metriclib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.AtomicFiles;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * 只追加审计表：每行一条 AuditRecord（JSON Lines），不提供任何改写 / 删除接口。
 * 幂等键 = 审计内容哈希（不含生成时刻），重复提交同一动作不产生重复记录。
 * recordChange 逐字段比较前后口径，只把真正变化的字段落进 field_diff。
 */
public final class AuditTrail {

    public static final String ACTION_SUBMIT_REVIEW = "SUBMIT_REVIEW";
    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_PUBLISH = "PUBLISH";
    public static final String ACTION_CHANGE = "CHANGE";

    private final Path file;

    public AuditTrail(Path file) {
        this.file = file;
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) AtomicFiles.ensureDir(parent);
    }

    /** 提交复核：操作人即提交人，此动作尚不带审批结论。 */
    public AuditRecord submitReview(String code, String version, String actor) {
        return append(code, version, actor, ACTION_SUBMIT_REVIEW, List.of(), "", "");
    }

    /** 审批通过：必须带审批人与意见。 */
    public AuditRecord approve(String code, String version, String actor,
                               String approver, String approvalNote) {
        requireNonBlank(approver, "审批人");
        requireNonBlank(approvalNote, "审批意见");
        return append(code, version, actor, ACTION_APPROVE, List.of(), approver, approvalNote);
    }

    /** 驳回：必须带审批人与意见。 */
    public AuditRecord reject(String code, String version, String actor,
                              String approver, String approvalNote) {
        requireNonBlank(approver, "审批人");
        requireNonBlank(approvalNote, "审批意见");
        return append(code, version, actor, ACTION_REJECT, List.of(), approver, approvalNote);
    }

    /**
     * 登记 PUBLISHED：未经 approve 不得出现 PUBLISHED，缺审批时抛 E_PUBLISH_001。
     * 与 hasApprovalBeforePublish 配套，测试可据此断言「approve 前无 PUBLISHED」。
     */
    public AuditRecord recordPublish(String code, String version, String actor) {
        if (!hasApprovalBeforePublish(code, version)) {
            throw ErrorCodes.error(ErrorCodes.E_PUBLISH_001,
                    "reason", "口径 " + code + "|" + version + " 未经 approve，不得出现 PUBLISHED");
        }
        return append(code, version, actor, ACTION_PUBLISH, List.of(), "", "");
    }

    /** 计算字段级 diff：逐字段比较前后口径，只记录真正变化的字段。 */
    public AuditRecord recordChange(String code, String version, String actor,
                                    MetricDefinition before, MetricDefinition after) {
        ObjectNode b = before.toJson();
        ObjectNode a = after.toJson();
        List<AuditRecord.FieldDiff> diffs = new ArrayList<>();
        Iterator<String> fields = a.fieldNames();
        while (fields.hasNext()) {
            String f = fields.next();
            JsonNode bv = b.get(f);
            JsonNode av = a.get(f);
            if (!Objects.equals(bv, av)) {
                diffs.add(new AuditRecord.FieldDiff(f,
                        bv == null ? "" : bv.toString(),
                        av == null ? "" : av.toString()));
            }
        }
        return append(code, version, actor, ACTION_CHANGE, diffs, "", "");
    }

    /** 按 indicator_code 反查完整变更链，按时间升序（同刻保持稳定追加次序）。 */
    public List<AuditRecord> chain(String code) {
        List<AuditRecord> out = new ArrayList<>();
        for (AuditRecord r : readAll()) {
            if (code.equals(r.getIndicatorCode())) out.add(r);
        }
        out.sort(Comparator.comparing(AuditRecord::getTimestamp));
        return List.copyOf(out);
    }

    /** 变更链按 definition_version 过滤。 */
    public List<AuditRecord> chain(String code, String version) {
        List<AuditRecord> out = new ArrayList<>();
        for (AuditRecord r : chain(code)) {
            if (version.equals(r.getDefinitionVersion())) out.add(r);
        }
        return List.copyOf(out);
    }

    /** 该版本是否已具备发布前置审批（链中存在 approve）。 */
    public boolean hasApprovalBeforePublish(String code, String version) {
        for (AuditRecord r : chain(code, version)) {
            if (ACTION_APPROVE.equals(r.getAction())) return true;
        }
        return false;
    }

    private AuditRecord append(String code, String version, String actor, String action,
                               List<AuditRecord.FieldDiff> diffs,
                               String approver, String approvalNote) {
        String id = idOf(code, version, actor, action, diffs, approver, approvalNote);
        for (AuditRecord r : readAll()) {
            if (id.equals(r.getAuditId())) return r;
        }
        AuditRecord rec = new AuditRecord(id, code, version, actor, action,
                Instant.now().toString(), diffs, approver, approvalNote);
        try {
            Files.writeString(file, rec.toJson().toString() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new AppException(ErrorCodes.E_AUDIT_001,
                    "审计表追加写入失败：" + file + "：" + e.getMessage(), e);
        }
        return rec;
    }

    /** 幂等键：审计内容（业务字段 + diff + 审批信息）哈希，不含时间戳。 */
    private static String idOf(String code, String version, String actor, String action,
                               List<AuditRecord.FieldDiff> diffs,
                               String approver, String approvalNote) {
        StringBuilder sb = new StringBuilder();
        sb.append(code).append('|').append(version).append('|').append(actor).append('|').append(action);
        for (AuditRecord.FieldDiff d : diffs) {
            sb.append('|').append(d.getField()).append(':')
                    .append(d.getBefore()).append("=>").append(d.getAfter());
        }
        sb.append('|').append(approver).append('|').append(approvalNote);
        return Hashes.sha256(sb.toString());
    }

    private List<AuditRecord> readAll() {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            List<AuditRecord> out = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                out.add(AuditRecord.fromJson(JsonSupport.parse(line)));
            }
            return out;
        } catch (IOException e) {
            throw new AppException(ErrorCodes.E_AUDIT_001,
                    "读取审计表失败：" + file + "：" + e.getMessage(), e);
        }
    }

    private static void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw ErrorCodes.error(ErrorCodes.E_AUDIT_001,
                    "reason", "审批动作缺少" + label);
        }
    }
}
