package com.infection.metric.expr;

import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.FieldDef;
import com.infection.metric.common.FieldDictionary;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段字典驱动的类型推断与字段缺失拦截（任务31）。
 *
 * <p>按 source + entity 取字段类型（用 {@link FieldDictionary}，结构同
 * GET /api/v1/metadata/fields）；比较符与字典类型不匹配报 E_TYPE_002 并说明
 * 期望类型与实际类型；值类型与字段声明类型不符报 E_TYPE_003；口径声明了但
 * 字典里没有的字段 → 显式失败（E_FIELD_001，文案带 code/field/source）。</p>
 *
 * <p>字典版本快照：口径记录所用字典版本，字典变化时经 {@link #needsReview(String)}
 * 与 {@link #reviewMessage(String, String)} 提示该口径需复核。</p>
 */
public final class FieldTypeChecker {

    private final FieldDictionary dictionary;
    private final String source;

    public FieldTypeChecker(FieldDictionary dictionary) {
        this(dictionary, "字典数据源");
    }

    public FieldTypeChecker(FieldDictionary dictionary, String source) {
        if (dictionary == null) {
            throw ExpressionErrors.error(ErrorCodes.E_PARSE_003, "reason", "字段字典为空");
        }
        this.dictionary = dictionary;
        this.source = source == null || source.isBlank() ? "字典数据源" : source;
    }

    public String dictionaryVersion() {
        return dictionary.version();
    }

    /** 按 source + entity 精确取字段类型；任一过滤条件不匹配返回 null。 */
    public String typeOf(String src, String entity, String field) {
        FieldDef d = dictionary.get(field);
        if (d == null) return null;
        if (src != null && !src.isBlank() && !src.equals(d.getSource())) return null;
        if (entity != null && !entity.isBlank() && !entity.equals(d.getEntity())) return null;
        return d.getType();
    }

    public FieldDef fieldDef(String src, String entity, String field) {
        return typeOf(src, entity, field) == null ? null : dictionary.get(field);
    }

    /** 逐叶子校验：字段存在性、比较符与字典类型、值类型。 */
    public void check(ConditionTree tree, String code) {
        if (tree == null) {
            throw ExpressionErrors.error(ErrorCodes.E_PARSE_003, "reason", "条件树为空");
        }
        for (ConditionTree leaf : tree.leaves()) {
            String field = leaf.getField();
            if (!dictionary.has(field)) {
                throw ExpressionErrors.error(ErrorCodes.E_FIELD_001,
                        "code", code, "field", field, "source", source);
            }
            FieldDef def = dictionary.get(field);
            String type = def.getType();
            String cmp = leaf.getCmp();
            if (!cmpAllowed(type, cmp)) {
                throw ExpressionErrors.error(ErrorCodes.E_TYPE_002,
                        "field", field, "cmp", cmp,
                        "expected", "字段字典类型 " + type + " 适用的比较符",
                        "actual", "比较符 " + cmp + "（要求 " + requiredValueType(cmp) + "）",
                        "type", type, "code", code, "source", source);
            }
            checkValueType(leaf, def, code);
        }
    }

    private static boolean cmpAllowed(String type, String cmp) {
        switch (type) {
            case "number":  return !"contains".equals(cmp);
            case "boolean": return "eq".equals(cmp) || "ne".equals(cmp);
            default:        return true; // string / date / enum 支持全部比较符
        }
    }

    private void checkValueType(ConditionTree leaf, FieldDef def, String code) {
        String type = def.getType();
        Object v = leaf.getValue();
        if ("number".equals(type)) {
            for (Object x : flatten(v)) {
                if (x != null && !ConditionTree.isNumericValue(x)) {
                    throw ExpressionErrors.error(ErrorCodes.E_TYPE_003,
                            "cmp", leaf.getCmp(), "field", leaf.getField(),
                            "expected", "数值", "actual", String.valueOf(x),
                            "type", type, "code", code, "source", source);
                }
            }
        } else if ("boolean".equals(type)) {
            for (Object x : flatten(v)) {
                if (x instanceof Boolean) continue;
                String s = String.valueOf(x);
                if (!"true".equalsIgnoreCase(s) && !"false".equalsIgnoreCase(s)
                        && !"真".equals(s) && !"假".equals(s)) {
                    throw ExpressionErrors.error(ErrorCodes.E_TYPE_003,
                            "cmp", leaf.getCmp(), "field", leaf.getField(),
                            "expected", "布尔", "actual", s,
                            "type", type, "code", code, "source", source);
                }
            }
        }
    }

    private static List<Object> flatten(Object v) {
        List<Object> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            out.addAll(l);
        } else {
            out.add(v);
        }
        return out;
    }

    private static String requiredValueType(String cmp) {
        switch (cmp) {
            case "contains": return "字符串";
            case "between":  return "区间列表";
            case "in":
            case "not_in":   return "集合列表";
            default:         return "标量";
        }
    }

    /** 字典版本是否变化：口径记录版本为空或与当前不一致 → 需复核。 */
    public boolean needsReview(String recordedVersion) {
        return recordedVersion == null || !dictionary.version().equals(recordedVersion);
    }

    /** 字典版本复核提示（含口径编码与新旧版本）。 */
    public String reviewMessage(String code, String recordedVersion) {
        if (!needsReview(recordedVersion)) {
            return "口径 " + code + " 使用字典版本 " + recordedVersion
                    + "，与当前字典一致，无需复核";
        }
        return "口径 " + code + " 记录的字典版本为 "
                + (recordedVersion == null ? "(未记录)" : recordedVersion)
                + "，当前字典版本已变为 " + dictionary.version() + "，该口径需复核";
    }
}
