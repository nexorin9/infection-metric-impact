package com.infection.metric.expr;

import com.infection.metric.common.DataRecord;
import com.infection.metric.common.ErrorCodes;

import java.util.Map;

/**
 * 条件树求值器（任务6）：对一条 {@link DataRecord} 或 {@code Map<String,Object>} 求值条件树。
 *
 * <p>支持全部 10 个比较符（经 {@link SetSemantics}），AND/OR 短路；
 * 求值前经 {@link TypeChecker} 做形状校验、经 {@link FieldValidator} 做字段存在性与脏数据校验。
 * 字段缺失显式失败（E_FIELD_001），空值按 {@link NullPolicy} 显式声明的语义处理
 * （FAIL 抛 E_DATA_001 / NO_MATCH 该条件不计入），绝不静默按 0 计入分母。</p>
 */
public final class Evaluator {

    /** 空值语义：口径必须显式声明，不得隐式按 0。 */
    public enum NullPolicy {
        /** 显式失败：空值抛 E_DATA_001。 */
        FAIL,
        /** 口径显式声明「空值不计入」：该条件判为 false，但不吞错误。 */
        NO_MATCH
    }

    private Evaluator() {}

    public static boolean eval(ConditionTree tree, DataRecord rec) {
        return eval(tree, rec, NullPolicy.FAIL, "(未指定口径)", "当前记录");
    }

    public static boolean eval(ConditionTree tree, Map<String, Object> map) {
        return eval(tree, toRecord(map), NullPolicy.FAIL, "(未指定口径)", "当前记录");
    }

    /** 带口径编码与数据源名的求值，字段缺失文案可定位到具体口径/数据源。 */
    public static boolean eval(ConditionTree tree, DataRecord rec, String code, String source) {
        return eval(tree, rec, NullPolicy.FAIL, code, source);
    }

    public static boolean eval(ConditionTree tree, DataRecord rec, NullPolicy policy) {
        return eval(tree, rec, policy, "(未指定口径)", "当前记录");
    }

    public static boolean eval(ConditionTree tree, DataRecord rec, NullPolicy policy,
                               String code, String source) {
        TypeChecker.check(tree);
        new FieldValidator().validate(tree, rec, code, source);
        return evalNode(tree, rec, policy, code, source);
    }

    private static boolean evalNode(ConditionTree node, DataRecord rec, NullPolicy policy,
                                    String code, String source) {
        if (node.isLeaf()) {
            String field = node.getField();
            if (!rec.has(field)) {
                throw ExpressionErrors.error(ErrorCodes.E_FIELD_001,
                        "code", code, "field", field, "source", source);
            }
            Object actual = rec.getOrNull(field);
            boolean blank = actual == null || (actual instanceof String s && s.isBlank());
            if (blank) {
                if (policy == NullPolicy.FAIL) {
                    throw ExpressionErrors.error(ErrorCodes.E_DATA_001,
                            "field", field, "reason", "空值，不得静默按 0 计入分母");
                }
                return false; // NO_MATCH：口径显式声明的空值语义
            }
            return SetSemantics.test(node.getCmp(), actual, node.getValue());
        }
        if ("OR".equals(node.getOp())) {
            for (ConditionTree child : node.getChildren()) {
                if (evalNode(child, rec, policy, code, source)) {
                    return true; // OR 任一真即短路
                }
            }
            return false;
        }
        for (ConditionTree child : node.getChildren()) {
            if (!evalNode(child, rec, policy, code, source)) {
                return false; // AND 任一假即短路
            }
        }
        return true;
    }

    private static DataRecord toRecord(Map<String, Object> map) {
        DataRecord.Builder b = DataRecord.builder();
        if (map != null) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                b.put(e.getKey(), e.getValue());
            }
        }
        return b.build();
    }
}
