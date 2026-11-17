package com.infection.metric.ui;

import com.infection.metric.common.ErrorCodes;

/**
 * 影响面展开的深度控制与展开预算（纯逻辑）。
 *
 * <p>默认只返回直接依赖；跨层需显式请求并在结果中标注深度；深度非法时抛 E_GRAPH_006 并给出合法范围。
 * 超预算时<strong>不抛异常、不卡死</strong>，而是返回带明确规模的 {@link BudgetHint} 供界面提示，
 * 用户仍可查看已展开部分。</p>
 */
public final class DepthControl {

    /** 默认深度：只看直接依赖。 */
    public static final int DEFAULT_DEPTH = 1;
    /** 层数上限，与 M2-D 展开预算一致。 */
    public static final int MAX_DEPTH = 5;
    /** 单次展开节点上限。 */
    public static final int MAX_NODES = 500;

    private int currentDepth = DEFAULT_DEPTH;

    public int currentDepth() { return currentDepth; }
    public int defaultDepth() { return DEFAULT_DEPTH; }
    public int maxDepth() { return MAX_DEPTH; }
    public int maxNodes() { return MAX_NODES; }

    /** 显式请求跨层：合法范围 1..MAX_DEPTH，非法时抛错且当前深度不变。 */
    public int requestDepth(int depth) {
        if (depth < 1 || depth > MAX_DEPTH) {
            throw ErrorCodes.error(ErrorCodes.E_GRAPH_006,
                    "depth", String.valueOf(depth),
                    "合法范围", "1~" + MAX_DEPTH);
        }
        this.currentDepth = depth;
        return depth;
    }

    /** 回到默认「直接依赖」。 */
    public void reset() { this.currentDepth = DEFAULT_DEPTH; }

    /** 深度标注文案：1 层为「直接依赖」，其余为「第 N 层依赖」。 */
    public String depthLabel(int depth) {
        return depth == 1 ? "直接依赖" : "第 " + depth + " 层依赖";
    }

    /** 展开预算检查：返回提示结果，不抛异常、不静默截断。 */
    public BudgetHint checkBudget(int actualNodes) {
        boolean exceeded = actualNodes > MAX_NODES;
        String message = exceeded
                ? "本次展开共 " + actualNodes + " 个节点，超过单节点预算 " + MAX_NODES
                        + " 个；请缩小范围或显式提高预算，当前仅显示已展开部分。"
                : "展开规模 " + actualNodes + " 个节点，在预算 " + MAX_NODES + " 以内。";
        return new BudgetHint(exceeded, message, actualNodes, MAX_NODES);
    }

    /** 预算检查结果（纯数据）。 */
    public static final class BudgetHint {
        private final boolean exceeded;
        private final String message;
        private final int actual;
        private final int limit;
        public BudgetHint(boolean exceeded, String message, int actual, int limit) {
            this.exceeded = exceeded;
            this.message = message;
            this.actual = actual;
            this.limit = limit;
        }
        public boolean isExceeded() { return exceeded; }
        public String getMessage() { return message; }
        public int getActual() { return actual; }
        public int getLimit() { return limit; }
    }
}
