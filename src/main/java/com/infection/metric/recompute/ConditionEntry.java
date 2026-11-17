package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.util.Objects;

/**
 * 条件级条目：两版口径做集合差的最小单位（任务7）。
 *
 * <p>{@code value} 是取值串：归一化通道下为 canonical form 的取值
 * （集合去重并排序、in/not_in 元素顺序无关）；原始通道下保留原始顺序。
 * 字段顺序无关由「条目集合」而非「列表」承载。</p>
 */
public final class ConditionEntry {

    private final TreePosition position;
    private final String field;
    private final String cmp;
    private final String value;

    public ConditionEntry(TreePosition position, String field, String cmp, String value) {
        this.position = Objects.requireNonNull(position, "position");
        this.field = field == null ? "" : field;
        this.cmp = cmp == null ? "" : cmp;
        this.value = value == null ? "" : value;
    }

    public TreePosition getPosition() { return position; }
    public String getField() { return field; }
    public String getCmp() { return cmp; }
    public String getValue() { return value; }

    /** 差异条目的依据引用，供归因层的 evidenceRef 指向具体条件。 */
    public String evidenceRef() {
        return position.name() + ":" + field + ":" + cmp + ":" + value;
    }

    public String renderCn() {
        return position.getCn() + " 字段 " + field + "（" + cmp + "）取值 " + value;
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("position", position.name());
        o.put("position_cn", position.getCn());
        o.put("field", field);
        o.put("cmp", cmp);
        o.put("value", value);
        return o;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ConditionEntry e)) return false;
        return position == e.position && field.equals(e.field)
                && cmp.equals(e.cmp) && value.equals(e.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(position, field, cmp, value);
    }

    @Override
    public String toString() { return renderCn(); }
}
