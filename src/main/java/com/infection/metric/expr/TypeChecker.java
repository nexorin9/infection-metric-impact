package com.infection.metric.expr;

import com.infection.metric.common.ErrorCodes;

import java.util.List;

/**
 * 条件树类型检查（任务6）：校验「比较符 ↔ 值形状」是否匹配，不匹配抛 E_TYPE_002。
 *
 * <ul>
 *   <li>between 只接受长度为 2 的列表 [lo,hi]（可为数值对或日期/字符串对）；</li>
 *   <li>in / not_in 只接受列表（允许空列表）；</li>
 *   <li>contains 只接受字符串；</li>
 *   <li>eq/ne/gte/lte/gt/lt 只接受标量（字符串/数值/布尔），不接受列表。</li>
 * </ul>
 */
public final class TypeChecker {

    private TypeChecker() {}

    /** 递归检查整棵条件树；任意叶子不匹配即抛 E_TYPE_002。 */
    public static void check(ConditionTree tree) {
        if (tree == null) {
            throw ExpressionErrors.error(ErrorCodes.E_PARSE_003, "field", "(空条件树)");
        }
        if (tree.isLeaf()) {
            checkLeaf(tree);
            return;
        }
        for (ConditionTree child : tree.getChildren()) {
            check(child);
        }
    }

    private static void checkLeaf(ConditionTree leaf) {
        String cmp = leaf.getCmp();
        String field = leaf.getField();
        Object v = leaf.getValue();
        boolean ok;
        switch (cmp) {
            case "between":
                ok = v instanceof List<?> l && l.size() == 2;
                break;
            case "in":
            case "not_in":
                ok = v instanceof List<?>;
                break;
            case "contains":
                ok = v instanceof String;
                break;
            default: // eq / ne / gte / lte / gt / lt
                ok = !(v instanceof List<?>);
                break;
        }
        if (!ok) {
            throw ExpressionErrors.error(ErrorCodes.E_TYPE_002,
                    "field", field, "cmp", cmp,
                    "expected", expectedShape(cmp), "actual", shapeOf(v));
        }
    }

    static String expectedShape(String cmp) {
        switch (cmp) {
            case "between": return "长度为 2 的 [lo,hi] 列表";
            case "in":
            case "not_in": return "列表 {...}";
            case "contains": return "字符串";
            default: return "标量（字符串/数值/布尔）";
        }
    }

    static String shapeOf(Object v) {
        if (v == null) return "null";
        if (v instanceof List<?> l) return "列表(size=" + l.size() + ")";
        if (v instanceof Boolean) return "布尔";
        if (v instanceof Number) return "数值";
        if (v instanceof String) return "字符串";
        return v.getClass().getSimpleName();
    }
}
