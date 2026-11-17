package com.infection.metric.expr;

import com.infection.metric.common.ErrorCodes;

import java.math.BigDecimal;
import java.util.List;

/**
 * 集合与区间边界语义（任务29）：between / in / not_in / contains 及六个标量比较符。
 *
 * <ul>
 *   <li>between 为闭区间 [lo,hi]：上下界相等成立；lo&gt;hi 抛 E_SEM_001「区间上下界颠倒」；</li>
 *   <li>数值区间与字符串区间分别处理，区间两端或集合元素数值/字符串混排抛 E_SEM_002；</li>
 *   <li>in 为集合成员判定，in {} 恒假；not_in 为非成员判定，not_in {} 恒真，空集合不当解析错误；</li>
 *   <li>contains 为子串判定，空串视为包含（任何串都包含空串）。</li>
 * </ul>
 *
 * <p>集合成员用字符串相等判定，保留科室代码等带前导零编码的字面语义；
 * 大小/区间比较在两操作数均为数值时用 BigDecimal，否则按字符串字典序。</p>
 */
public final class SetSemantics {

    private SetSemantics() {}

    /** 按比较符对「记录实际值 vs 条件值」判定，供求值器与预编译计划复用。 */
    public static boolean test(String cmp, Object actual, Object conditionValue) {
        if (cmp == null) {
            throw ExpressionErrors.error(ErrorCodes.E_PARSE_002, "cmp", "(空)");
        }
        switch (cmp) {
            case "between": return between(actual, asList(conditionValue, cmp));
            case "in":      return in(actual, asList(conditionValue, cmp));
            case "not_in":  return notIn(actual, asList(conditionValue, cmp));
            case "contains":return contains(actual, conditionValue);
            case "eq":      return compareValues(actual, conditionValue) == 0;
            case "ne":      return compareValues(actual, conditionValue) != 0;
            case "gte":     return compareValues(actual, conditionValue) >= 0;
            case "lte":     return compareValues(actual, conditionValue) <= 0;
            case "gt":      return compareValues(actual, conditionValue) > 0;
            case "lt":      return compareValues(actual, conditionValue) < 0;
            default:
                throw ExpressionErrors.error(ErrorCodes.E_PARSE_002, "cmp", cmp);
        }
    }

    /** between 闭区间 [lo,hi]。lo&gt;hi 抛 E_SEM_001，两端类型混排抛 E_SEM_002。 */
    public static boolean between(Object actual, List<?> bounds) {
        if (bounds == null || bounds.size() != 2) {
            throw ExpressionErrors.error(ErrorCodes.E_TYPE_002,
                    "field", "(between)", "cmp", "between",
                    "expected", "[lo,hi]", "actual", String.valueOf(bounds));
        }
        Object lo = bounds.get(0);
        Object hi = bounds.get(1);
        boolean loNum = isNum(lo);
        boolean hiNum = isNum(hi);
        if (loNum != hiNum) {
            throw ExpressionErrors.error(ErrorCodes.E_SEM_002,
                    "field", "(between)", "lo", str(lo), "hi", str(hi));
        }
        if (compareValues(lo, hi) > 0) {
            throw ExpressionErrors.error(ErrorCodes.E_SEM_001,
                    "field", "(between)", "lo", str(lo), "hi", str(hi));
        }
        return compareValues(actual, lo) >= 0 && compareValues(actual, hi) <= 0;
    }

    /** in 集合成员；空集合恒假；集合内数值/字符串混排抛 E_SEM_002。 */
    public static boolean in(Object actual, List<?> set) {
        if (set == null || set.isEmpty()) {
            return false;
        }
        checkHomogeneous(set);
        for (Object e : set) {
            if (memberEquals(actual, e)) return true;
        }
        return false;
    }

    /** not_in 非成员；空集合恒真。 */
    public static boolean notIn(Object actual, List<?> set) {
        return !in(actual, set);
    }

    /** contains 子串；子串为空串时恒为包含。 */
    public static boolean contains(Object actual, Object sub) {
        return str(actual).contains(str(sub));
    }

    /** 集合成员相等判定（对外的语义入口）。 */
    public static boolean matches(Object a, Object b) {
        return memberEquals(a, b);
    }

    /** 两操作数的大小比较：均为数值用 BigDecimal，否则按字符串字典序。 */
    public static int compareValues(Object a, Object b) {
        if (isNum(a) && isNum(b)) {
            try {
                return new BigDecimal(str(a)).compareTo(new BigDecimal(str(b)));
            } catch (NumberFormatException ignore) {
                // 理论不会到达（isNumericValue 已校验），保留字符串兜底
            }
        }
        return str(a).compareTo(str(b));
    }

    private static void checkHomogeneous(List<?> set) {
        boolean sawNum = false;
        boolean sawStr = false;
        for (Object e : set) {
            if (isNum(e)) sawNum = true; else sawStr = true;
        }
        if (sawNum && sawStr) {
            throw ExpressionErrors.error(ErrorCodes.E_SEM_002, "field", "(集合)");
        }
    }

    private static boolean memberEquals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        return str(a).equals(str(b));
    }

    private static boolean isNum(Object o) {
        return ConditionTree.isNumericValue(o);
    }

    private static String str(Object o) {
        return String.valueOf(o);
    }

    private static List<?> asList(Object v, String cmp) {
        if (v instanceof List<?> l) return l;
        throw ExpressionErrors.error(ErrorCodes.E_TYPE_002,
                "field", "(" + cmp + ")", "cmp", cmp,
                "expected", "列表", "actual", String.valueOf(v));
    }
}
