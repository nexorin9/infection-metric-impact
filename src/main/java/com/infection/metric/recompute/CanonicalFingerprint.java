package com.infection.metric.recompute;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.expr.Canonicalizer;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;

/**
 * 口径归一化与内容指纹（任务33）：复用 / 封装 {@link Canonicalizer}。
 *
 * <p>规范化规则由底座统一实现：AND / OR 子节点排序（交换律）、集合去重并排序、
 * in / not_in 元素顺序无关、between 上下界定序；指纹 = canonical form 的 sha256。
 * 同一指纹同时用于依赖边匹配（按分母内容哈希，不按名称）与复算缓存键，
 * 保证「同口径 = 同指纹」全链路一致。</p>
 */
public final class CanonicalFingerprint {

    private CanonicalFingerprint() {}

    /** 条件树指纹：canonical form 序列化后取 sha256。 */
    public static String ofTree(ConditionTree tree) {
        if (tree == null) {
            throw new AppException(ErrorCodes.E_PARSE_003, "口径条件树不可为空，无法计算指纹");
        }
        return Canonicalizer.fingerprint(tree);
    }

    /** 口径整体内容指纹（= {@link MetricDefinition#contentFingerprint()}）。 */
    public static String ofDefinition(MetricDefinition definition) {
        if (definition == null) {
            throw new AppException(ErrorCodes.E_PARSE_003, "口径条目不可为空，无法计算内容指纹");
        }
        return definition.contentFingerprint();
    }

    /** 依赖边匹配键：按分母定义内容哈希，不按名称匹配。 */
    public static String dependencyEdgeKey(MetricDefinition definition) {
        return definition.denominatorFingerprint();
    }

    /** 复算缓存键：口径规范化内容指纹（contentFingerprint）。 */
    public static String cacheKey(MetricDefinition definition) {
        return ofDefinition(definition);
    }

    /** 等价性判定：指纹相同即等价、不产生差异；指纹不同才逐条比较。 */
    public static boolean equivalent(ConditionTree a, ConditionTree b) {
        return Canonicalizer.equivalent(a, b);
    }

    /** 两个口径内容指纹相同即等价（注意含版本号，同内容不同版本视为不同口径）。 */
    public static boolean equivalent(MetricDefinition a, MetricDefinition b) {
        return a != null && b != null
                && a.contentFingerprint().equals(b.contentFingerprint());
    }

    /** 规范化后的 canonical form JSON 串（供对照与展示）。 */
    public static String canonicalJson(ConditionTree tree) {
        if (tree == null) {
            throw new AppException(ErrorCodes.E_PARSE_003, "口径条件树不可为空，无法生成 canonical form");
        }
        return Canonicalizer.canonicalJson(tree);
    }
}
