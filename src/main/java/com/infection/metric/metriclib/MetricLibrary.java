package com.infection.metric.metriclib;

import com.infection.metric.common.ErrorCodes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存口径库：以 (indicator_code | definition_version) 为联合主键，同一指标多版本并存。
 * 主键重复且口径内容不同时显式报冲突（不静默覆盖）；状态只能沿 MetricStatus 状态机推进；
 * 已发布版本内容不可直接改动，只能从它复制出新草稿版本后再编辑。
 */
public final class MetricLibrary {

    private final Map<String, MetricDefinition> defs = new LinkedHashMap<>();

    /** 新增口径；同主键内容相同则幂等，内容不同则抛 E_CONFLICT_001。 */
    public MetricDefinition put(MetricDefinition def) {
        String key = def.key();
        MetricDefinition existing = defs.get(key);
        if (existing != null) {
            if (existing.sameContent(def)) {
                return existing;
            }
            throw ErrorCodes.error(ErrorCodes.E_CONFLICT_001, "key", key)
                    .with("库内标题", existing.getTitle())
                    .with("本次提交标题", def.getTitle())
                    .hinting("同一 (indicator_code, definition_version) 已存在且内容不同，改用新版本号或先核对差异");
        }
        defs.put(key, def);
        return def;
    }

    public MetricDefinition get(String code, String version) {
        return defs.get(code + "|" + version);
    }

    /** 该指标的全部版本，按 definition_version 升序。 */
    public List<MetricDefinition> versions(String code) {
        List<MetricDefinition> out = new ArrayList<>();
        for (MetricDefinition d : defs.values()) {
            if (d.getIndicatorCode().equals(code)) out.add(d);
        }
        out.sort(Comparator.comparing(MetricDefinition::getDefinitionVersion));
        return List.copyOf(out);
    }

    /** 全部口径，按联合主键升序，保证遍历稳定。 */
    public List<MetricDefinition> all() {
        List<MetricDefinition> out = new ArrayList<>(defs.values());
        out.sort(Comparator.comparing(MetricDefinition::key));
        return List.copyOf(out);
    }

    /** 该指标最新（版本号最大）的已发布版本；没有已发布版本时返回 null。 */
    public MetricDefinition findPublished(String code) {
        return versions(code).stream()
                .filter(d -> d.getStatus() == MetricStatus.PUBLISHED)
                .max(Comparator.comparing(MetricDefinition::getDefinitionVersion))
                .orElse(null);
    }

    /**
     * 状态机推进。actor 为操作人；跃迁到 PUBLISHED 时必须带审批信息，
     * 四参重载用 actor 兼任 approver 并自动生成审批时间，六参重载可显式指定。
     * 非法跃迁由 MetricStatus.requireTransition 拦截，且在任何写回之前校验，故状态不变。
     */
    public MetricDefinition transition(String code, String version, MetricStatus target, String actor) {
        return transition(code, version, target, actor, null, null);
    }

    public MetricDefinition transition(String code, String version, MetricStatus target,
                                       String actor, String approver, String approvedAt) {
        MetricDefinition cur = requireExisting(code, version);
        cur.getStatus().requireTransition(target);

        String newApprover = cur.getApprover();
        String newApprovedAt = cur.getApprovedAt();
        if (target == MetricStatus.PUBLISHED) {
            String a = (approver != null && !approver.isBlank()) ? approver : actor;
            String t = (approvedAt != null && !approvedAt.isBlank()) ? approvedAt : Instant.now().toString();
            if (a == null || a.isBlank()) {
                throw ErrorCodes.error(ErrorCodes.E_AUDIT_001, "reason", "发布 PUBLISHED 缺少审批人 approver");
            }
            if (t == null || t.isBlank()) {
                throw ErrorCodes.error(ErrorCodes.E_AUDIT_001, "reason", "发布 PUBLISHED 缺少审批时间 approvedAt");
            }
            newApprover = a;
            newApprovedAt = t;
        }

        MetricDefinition next = cur.toBuilder()
                .status(target).approver(newApprover).approvedAt(newApprovedAt).build();
        defs.put(cur.key(), next);
        return next;
    }

    /**
     * 从指定版本复制口径内容，生成全新 DRAFT 版本（清空审批信息）。
     * 复制出的对象与原版本完全独立，编辑草稿不会影响已 PUBLISHED 版本的任何字段。
     */
    public MetricDefinition createDraft(String code, String fromVersion, String newVersion) {
        MetricDefinition src = requireExisting(code, fromVersion);
        String newKey = code + "|" + newVersion;
        if (defs.containsKey(newKey)) {
            throw ErrorCodes.error(ErrorCodes.E_CONFLICT_001, "key", newKey)
                    .hinting("目标草稿版本已存在，请更换 newVersion");
        }
        MetricDefinition draft = MetricDefinition.builder(code, newVersion)
                .title(src.getTitle())
                .numerator(src.getNumerator())
                .denominator(src.getDenominator())
                .exclusions(src.getExclusions())
                .deptCodes(src.getDeptCodes())
                .periods(src.getPeriods())
                .dataSources(src.getDataSources())
                .effectiveFrom(src.getEffectiveFrom())
                .effectiveTo(src.getEffectiveTo())
                .status(MetricStatus.DRAFT)
                .author(src.getAuthor())
                .approver("")
                .approvedAt("")
                .dictionaryVersion(src.getDictionaryVersion())
                .build();
        defs.put(newKey, draft);
        return draft;
    }

    /**
     * 修改草稿内容（replacement 的联合主键须已存在）。
     * 已 PUBLISHED / 送审中 / 已 RETIRED 版本不允许直接改内容，也不允许借本方法偷偷发布。
     */
    public MetricDefinition updateContent(MetricDefinition replacement) {
        MetricDefinition cur = requireExisting(replacement.getIndicatorCode(), replacement.getDefinitionVersion());
        if (cur.getStatus() == MetricStatus.PUBLISHED
                || cur.getStatus() == MetricStatus.PENDING_REVIEW
                || cur.getStatus() == MetricStatus.RETIRED) {
            throw ErrorCodes.error(ErrorCodes.E_STATE_001,
                            "from", cur.getStatus().name(), "to", "内容修改")
                    .hinting("已发布 / 送审中 / 已停用版本不可直接改内容，请先用 createDraft 新建草稿版本");
        }
        if (replacement.getStatus() == MetricStatus.PUBLISHED) {
            throw ErrorCodes.error(ErrorCodes.E_PUBLISH_001,
                    "reason", "不得借 updateContent 直接发布，请走 transition 状态机");
        }
        defs.put(cur.key(), replacement);
        return replacement;
    }

    private MetricDefinition requireExisting(String code, String version) {
        String key = code + "|" + version;
        MetricDefinition d = defs.get(key);
        if (d == null) {
            throw ErrorCodes.error(ErrorCodes.E_GRAPH_004, "id", key)
                    .hinting("口径库中不存在该主键，请先 put 或 createDraft");
        }
        return d;
    }
}
