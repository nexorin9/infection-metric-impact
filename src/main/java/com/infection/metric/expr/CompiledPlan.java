package com.infection.metric.expr;

import com.infection.metric.common.DataRecord;
import com.infection.metric.common.ErrorCodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 条件树预编译求值计划（任务32）：把条件树编译为可复用的求值计划，
 * 常量与比较符在编译期预解析（集合成员预建 HashSet、区间边界预存、类型一致性预计算），
 * 避免每次求值重复解析。
 *
 * <p>AND 任一假即短路、OR 任一真即短路，且短路不改变结果（可用
 * {@link #referenceEval(ConditionTree, DataRecord)} 的全量基准比对）。
 * 批量求值中字段缺失显式失败（E_FIELD_001，不按 false 处理），
 * 空值按 {@link Evaluator.NullPolicy} 显式声明的语义处理。</p>
 */
public final class CompiledPlan {

    private final PlanNode root;
    private final int leafCount;
    private final Evaluator.NullPolicy policy;
    private final String code;
    private final String source;
    private int lastLeafEvals;

    private CompiledPlan(PlanNode root, int leafCount, Evaluator.NullPolicy policy,
                        String code, String source) {
        this.root = root;
        this.leafCount = leafCount;
        this.policy = policy;
        this.code = code;
        this.source = source;
    }

    public static CompiledPlan compile(ConditionTree tree) {
        return compile(tree, Evaluator.NullPolicy.FAIL, "(未指定口径)", "批量记录");
    }

    public static CompiledPlan compile(ConditionTree tree, String code, String source) {
        return compile(tree, Evaluator.NullPolicy.FAIL, code, source);
    }

    public static CompiledPlan compile(ConditionTree tree, Evaluator.NullPolicy policy,
                                      String code, String source) {
        TypeChecker.check(tree);
        PlanNode node = compileNode(tree, policy, code, source);
        return new CompiledPlan(node, tree.leaves().size(), policy, code, source);
    }

    private static PlanNode compileNode(ConditionTree t, Evaluator.NullPolicy policy,
                                        String code, String source) {
        if (t.isLeaf()) {
            return new LeafPlan(t, code, source);
        }
        List<PlanNode> kids = new ArrayList<>();
        for (ConditionTree c : t.getChildren()) {
            kids.add(compileNode(c, policy, code, source));
        }
        return new BranchPlan("AND".equals(t.getOp()), kids);
    }

    /** 对一条记录求值；短路实际执行的叶子数见 {@link #lastLeafEvals()}。 */
    public boolean eval(DataRecord rec) {
        EvalContext ctx = new EvalContext(policy);
        boolean result = root.eval(rec, ctx);
        lastLeafEvals = ctx.leafEvals;
        return result;
    }

    /** 上次 eval 实际执行的叶子数（用于验证短路确实生效）。 */
    public int lastLeafEvals() {
        return lastLeafEvals;
    }

    public int leafCount() {
        return leafCount;
    }

    /** 批量求值，返回匹配条数。 */
    public int evalAll(List<DataRecord> records) {
        int matched = 0;
        for (DataRecord r : records) {
            if (eval(r)) matched++;
        }
        return matched;
    }

    // ---------------------------------------------------------------- 非短路基准

    /** 朴素全量（不短路）基准求值，用于验证短路不改变结果。 */
    public static boolean referenceEval(ConditionTree tree, DataRecord rec) {
        return referenceEval(tree, rec, Evaluator.NullPolicy.FAIL, "(基准)", "记录");
    }

    public static boolean referenceEval(ConditionTree tree, DataRecord rec,
                                        Evaluator.NullPolicy policy, String code, String source) {
        TypeChecker.check(tree);
        new FieldValidator().validate(tree, rec, code, source);
        return refNode(tree, rec, policy, code, source);
    }

    private static boolean refNode(ConditionTree node, DataRecord rec, Evaluator.NullPolicy policy,
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
                if (policy == Evaluator.NullPolicy.FAIL) {
                    throw ExpressionErrors.error(ErrorCodes.E_DATA_001,
                            "field", field, "reason", "空值，不得静默按 0 计入分母");
                }
                return false;
            }
            return SetSemantics.test(node.getCmp(), actual, node.getValue());
        }
        boolean isAnd = "AND".equals(node.getOp());
        boolean acc = isAnd;
        for (ConditionTree child : node.getChildren()) {
            boolean r = refNode(child, rec, policy, code, source); // 全量求值，不提前返回
            acc = isAnd ? (acc && r) : (acc || r);
        }
        return acc;
    }

    // ---------------------------------------------------------------- 计划节点

    private interface PlanNode {
        boolean eval(DataRecord rec, EvalContext ctx);
    }

    private static final class EvalContext {
        final Evaluator.NullPolicy policy;
        int leafEvals = 0;

        EvalContext(Evaluator.NullPolicy policy) {
            this.policy = policy;
        }
    }

    private static final class BranchPlan implements PlanNode {
        private final boolean isAnd;
        private final List<PlanNode> kids;

        BranchPlan(boolean isAnd, List<PlanNode> kids) {
            this.isAnd = isAnd;
            this.kids = kids;
        }

        @Override
        public boolean eval(DataRecord rec, EvalContext ctx) {
            if (isAnd) {
                for (PlanNode k : kids) {
                    if (!k.eval(rec, ctx)) return false; // AND 任一假即短路
                }
                return true;
            }
            for (PlanNode k : kids) {
                if (k.eval(rec, ctx)) return true;      // OR 任一真即短路
            }
            return false;
        }
    }

    private static final class LeafPlan implements PlanNode {
        private final String field;
        private final String cmp;
        private final Object value;
        private final String code;
        private final String source;
        private final List<?> bounds;      // between 预存边界
        private final Set<String> members; // in/not_in 预解析集合
        private final boolean homogeneous; // 集合内类型一致性，编译期预计算

        LeafPlan(ConditionTree t, String code, String source) {
            this.field = t.getField();
            this.cmp = t.getCmp();
            this.value = t.getValue();
            this.code = code;
            this.source = source;
            List<?> list = value instanceof List<?> l ? l : List.of();
            this.bounds = "between".equals(cmp) ? list : List.of();
            Set<String> m = new HashSet<>();
            boolean sawNum = false;
            boolean sawStr = false;
            if ("in".equals(cmp) || "not_in".equals(cmp)) {
                for (Object e : list) {
                    m.add(String.valueOf(e));
                    if (ConditionTree.isNumericValue(e)) sawNum = true; else sawStr = true;
                }
            }
            this.members = m;
            this.homogeneous = !(sawNum && sawStr);
        }

        @Override
        public boolean eval(DataRecord rec, EvalContext ctx) {
            ctx.leafEvals++;
            if (!rec.has(field)) {
                throw ExpressionErrors.error(ErrorCodes.E_FIELD_001,
                        "code", code, "field", field, "source", source);
            }
            Object actual = rec.getOrNull(field);
            boolean blank = actual == null || (actual instanceof String s && s.isBlank());
            if (blank) {
                if (ctx.policy == Evaluator.NullPolicy.FAIL) {
                    throw ExpressionErrors.error(ErrorCodes.E_DATA_001,
                            "field", field, "reason", "空值，不得静默按 0 计入分母");
                }
                return false; // 口径显式声明的空值语义
            }
            switch (cmp) {
                case "in":
                    if (!homogeneous) {
                        throw ExpressionErrors.error(ErrorCodes.E_SEM_002, "field", field);
                    }
                    return members.contains(String.valueOf(actual)); // 空集合恒假
                case "not_in":
                    if (!homogeneous) {
                        throw ExpressionErrors.error(ErrorCodes.E_SEM_002, "field", field);
                    }
                    return !members.contains(String.valueOf(actual)); // 空集合恒真
                case "between":
                    return SetSemantics.between(actual, bounds);
                default:
                    return SetSemantics.test(cmp, actual, value);
            }
        }
    }
}
