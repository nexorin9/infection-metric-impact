package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

/**
 * 条件级差异（任务7）：按条件逐条输出，带字段名与旧值 / 新值，可直接贴进差异说明。
 * 三桶：分子新增 / 排除新增 / 移除。
 */
public final class ConditionChange {

    /** 差异三桶。 */
    public enum Bucket {
        NUMERATOR_ADDED("分子新增"),
        EXCLUSION_ADDED("排除新增"),
        REMOVED("移除");

        private final String cn;
        Bucket(String cn) { this.cn = cn; }
        public String getCn() { return cn; }
    }

    private final Bucket bucket;
    private final TreePosition position;
    private final String field;
    private final String cmp;
    private final String oldValue;   // null 表示旧口径无此条目
    private final String newValue;   // null 表示新口径无此条目（被移除）

    public ConditionChange(Bucket bucket, TreePosition position, String field, String cmp,
                           String oldValue, String newValue) {
        this.bucket = bucket;
        this.position = position;
        this.field = field;
        this.cmp = cmp;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public Bucket getBucket() { return bucket; }
    public TreePosition getPosition() { return position; }
    public String getField() { return field; }
    public String getCmp() { return cmp; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }

    /** 依据引用：归因层据此把差异定位到具体条件条目。 */
    public String evidenceRef() {
        return position.name() + ":" + field + ":" + cmp;
    }

    public String renderCn() {
        String ov = oldValue == null ? "（无）" : oldValue;
        String nv = newValue == null ? "（无）" : newValue;
        return bucket.getCn() + "｜" + position.getCn() + " 字段 " + field
                + "（" + cmp + "）：旧值 " + ov + " → 新值 " + nv;
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("bucket", bucket.name());
        o.put("bucket_cn", bucket.getCn());
        o.put("position", position.name());
        o.put("position_cn", position.getCn());
        o.put("field", field);
        o.put("cmp", cmp);
        if (oldValue == null) o.putNull("old_value"); else o.put("old_value", oldValue);
        if (newValue == null) o.putNull("new_value"); else o.put("new_value", newValue);
        o.put("evidence_ref", evidenceRef());
        return o;
    }
}
