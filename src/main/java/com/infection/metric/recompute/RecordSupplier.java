package com.infection.metric.recompute;

/**
 * 复算取数函数接口（任务8 注入点）。
 *
 * <p>按季度返回分子 / 分母记录集。测试可注入计数取数次数的替身，验证
 * 「同口径重复复算命中缓存、不重复取数」；也可注入抛异常 / 缺字段记录，
 * 验证「取数失败或字段缺失时显式失败、不按 0 计入分母」。</p>
 */
@FunctionalInterface
public interface RecordSupplier {

    /** 按季度取数；取数失败可抛异常，由复算引擎统一转为 E_RECOMPUTE_001 显式失败。 */
    QuarterRecords fetch(String quarter) throws Exception;
}
