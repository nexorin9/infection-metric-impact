package com.infection.metric.recompute;

import com.infection.metric.common.AppException;
import com.infection.metric.common.DataRecord;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.expr.CompiledPlan;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复算引擎（任务8）：按已发布口径对指定季度复算分子与分母。
 *
 * <p>缓存键以口径规范化内容指纹 {@link MetricDefinition#contentFingerprint()} 为主，
 * 叠加季度与数据源版本隔离；同口径同季度重复复算命中缓存、不重复取数。</p>
 *
 * <p><b>失败不静默改数据</b>：取数失败或字段缺失 → 该指标本次复算显式失败
 * （状态 FAILED、分子分母为 null 而非 0），写进 {@link RunSummary} 的失败步骤，
 * 绝不按 0 计入分母；未发布口径直接抛 E_RECOMPUTE_002。</p>
 */
public final class RecomputeEngine {

    private final RecordSupplier supplier;
    private final String dataSourceVersion;
    private final RunSummary summary;
    private final Map<String, RecomputeResult> cache = new LinkedHashMap<>();
    private int fetchCount;

    public RecomputeEngine(RecordSupplier supplier) {
        this(supplier, "v1");
    }

    public RecomputeEngine(RecordSupplier supplier, String dataSourceVersion) {
        if (supplier == null) {
            throw new AppException(ErrorCodes.E_RECOMPUTE_001, "复算取数器 RecordSupplier 不可为空");
        }
        this.supplier = supplier;
        this.dataSourceVersion = dataSourceVersion == null || dataSourceVersion.isBlank()
                ? "v1" : dataSourceVersion;
        this.summary = new RunSummary(this.dataSourceVersion);
    }

    public RunSummary getSummary() { return summary; }
    public int getFetchCount() { return fetchCount; }
    public String getDataSourceVersion() { return dataSourceVersion; }

    /** 主复算入口：成功返回结果，失败返回显式失败结果（不抛，便于批量隔离）。 */
    public RecomputeResult recompute(MetricDefinition def, String quarter) {
        if (def == null || quarter == null || quarter.isBlank()) {
            throw new AppException(ErrorCodes.E_RECOMPUTE_001, "复算输入不可为空：口径或季度为空");
        }
        // 未发布口径不可复算：显式抛错并写入摘要的未发布清单
        if (def.getStatus() != MetricStatus.PUBLISHED) {
            summary.addUnpublished(def.getIndicatorCode(), def.getDefinitionVersion());
            throw ErrorCodes.error(ErrorCodes.E_RECOMPUTE_002, "code", def.getIndicatorCode());
        }

        String entryKey = def.contentFingerprint() + "@" + quarter + "@" + dataSourceVersion;
        RecomputeResult cached = cache.get(entryKey);
        if (cached != null) {
            RecomputeResult hit = cached.withCacheHit(true);
            summary.addResult(hit);
            return hit;
        }

        long t0 = System.nanoTime();
        QuarterRecords records;
        try {
            fetchCount++;
            records = supplier.fetch(quarter);
        } catch (Exception e) {
            return fail(def, quarter, "取数", describe(e));
        }
        long fetchMs = (System.nanoTime() - t0) / 1_000_000L;

        long c0 = System.nanoTime();
        try {
            ConditionTree numTree = bindQuarter(def.getNumerator(), quarter);
            ConditionTree denTree = bindQuarter(def.getDenominator(), quarter);
            int numerator = evalAll(numTree, records.getNumerator(), def);
            int denominator = evalAll(denTree, records.getDenominator(), def);
            long computeMs = (System.nanoTime() - c0) / 1_000_000L;

            RecomputeResult ok = RecomputeResult.success(def.getIndicatorCode(),
                    def.getDefinitionVersion(), quarter, numerator, denominator,
                    def.contentFingerprint(), fetchMs, computeMs);
            cache.put(entryKey, ok);
            summary.addResult(ok);
            summary.addFetchMillis(fetchMs);
            summary.addComputeMillis(computeMs);
            return ok;
        } catch (RuntimeException e) {
            return fail(def, quarter, "求值", describe(e));
        }
    }

    /** 严格入口：失败时抛 AppException（E_RECOMPUTE_001），供需要立即中断的调用方。 */
    public RecomputeResult recomputeOrThrow(MetricDefinition def, String quarter) {
        RecomputeResult r = recompute(def, quarter);
        if (r.isFailed()) {
            throw ErrorCodes.error(ErrorCodes.E_RECOMPUTE_001, "code", def.getIndicatorCode())
                    .hinting(r.getFailureReason());
        }
        return r;
    }

    /** 双版本对拍：同季度旧 / 新两版各复算一遍 → 分子差 / 分母差 / 口径差三段。 */
    public RecomputeComparison compare(MetricDefinition oldDef, MetricDefinition newDef, String quarter) {
        RecomputeResult oldResult = recompute(oldDef, quarter);
        RecomputeResult newResult = recompute(newDef, quarter);
        DiffResult diff = new DiffEngine().diff(oldDef, newDef);
        return new RecomputeComparison(oldDef, newDef, quarter, oldResult, newResult, diff);
    }

    // ---------------------------------------------------------------- 内部

    private int evalAll(ConditionTree tree, List<DataRecord> records, MetricDefinition def) {
        if (tree == null) {
            throw new AppException(ErrorCodes.E_RECOMPUTE_001,
                    "口径 " + def.getIndicatorCode() + " 缺少分子 / 分母条件树，不得按 0 计入");
        }
        CompiledPlan plan = CompiledPlan.compile(tree, def.getIndicatorCode(), dataSourceVersion);
        return plan.evalAll(records);
    }

    private RecomputeResult fail(MetricDefinition def, String quarter, String step, String detail) {
        AppException ex = ErrorCodes.error(ErrorCodes.E_RECOMPUTE_001, "code", def.getIndicatorCode());
        String reason = step + "失败：" + detail + "；" + ex.render();
        RecomputeResult r = RecomputeResult.failure(def.getIndicatorCode(),
                def.getDefinitionVersion(), quarter, def.contentFingerprint(), step, reason);
        summary.addResult(r);
        summary.addFailedStep(def.getIndicatorCode(), quarter, step, reason);
        // 失败结果不写缓存，避免后续复算命中错误数字
        return r;
    }

    /** 把参数化口径里的 {@code @quarter} 占位符绑定为具体季度值。 */
    public static ConditionTree bindQuarter(ConditionTree tree, String quarter) {
        if (tree == null) return null;
        if (tree.isLeaf()) {
            return ConditionTree.leaf(tree.getField(), tree.getCmp(),
                    bindValue(tree.getValue(), quarter));
        }
        List<ConditionTree> kids = new ArrayList<>();
        for (ConditionTree c : tree.getChildren()) kids.add(bindQuarter(c, quarter));
        return ConditionTree.branch(tree.getOp(), kids);
    }

    private static Object bindValue(Object v, String quarter) {
        if ("@quarter".equals(v)) return quarter;
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object e : list) out.add(bindValue(e, quarter));
            return out;
        }
        return v;
    }

    private static String describe(Throwable e) {
        if (e instanceof AppException a) return a.render();
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
