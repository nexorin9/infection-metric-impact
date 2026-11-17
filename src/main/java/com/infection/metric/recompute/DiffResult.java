package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * 两版口径结构化差异结果（任务7）：三桶齐全 —— 分子新增 / 排除新增 / 移除。
 * 归一化后等价的两个口径三桶皆空。
 */
public final class DiffResult {

    private final List<ConditionChange> numeratorAdded;
    private final List<ConditionChange> exclusionAdded;
    private final List<ConditionChange> removed;

    public DiffResult(List<ConditionChange> numeratorAdded,
                      List<ConditionChange> exclusionAdded,
                      List<ConditionChange> removed) {
        this.numeratorAdded = List.copyOf(numeratorAdded == null ? List.of() : numeratorAdded);
        this.exclusionAdded = List.copyOf(exclusionAdded == null ? List.of() : exclusionAdded);
        this.removed = List.copyOf(removed == null ? List.of() : removed);
    }

    public static DiffResult empty() {
        return new DiffResult(List.of(), List.of(), List.of());
    }

    public List<ConditionChange> getNumeratorAdded() { return numeratorAdded; }
    public List<ConditionChange> getExclusionAdded() { return exclusionAdded; }
    public List<ConditionChange> getRemoved() { return removed; }

    public boolean isEmpty() { return total() == 0; }

    public int total() {
        return numeratorAdded.size() + exclusionAdded.size() + removed.size();
    }

    /** 按桶顺序合并，供渲染器与归因层逐条消费。 */
    public List<ConditionChange> all() {
        List<ConditionChange> out = new ArrayList<>();
        out.addAll(numeratorAdded);
        out.addAll(exclusionAdded);
        out.addAll(removed);
        return out;
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.set("numeratorAdded", toArray(numeratorAdded));
        o.set("exclusionAdded", toArray(exclusionAdded));
        o.set("removed", toArray(removed));
        o.put("total", total());
        return o;
    }

    private static ArrayNode toArray(List<ConditionChange> changes) {
        ArrayNode arr = JsonSupport.array();
        for (ConditionChange c : changes) arr.add(c.toJson());
        return arr;
    }
}
