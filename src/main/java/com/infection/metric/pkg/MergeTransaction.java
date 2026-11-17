package com.infection.metric.pkg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.AtomicFiles;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 口径包合并事务边界：合并到一半失败不留半拉子口径。
 *
 * <p>用文件（调用方注入 @TempDir 目录）模拟本地口径库：
 * {@code definitions.json}（口径条目）、{@code index.json}（索引）、
 * {@code package_state.json}（口径包状态）、{@code audit.json}（审计）、
 * {@code summary.json}（运行摘要）。合并前记录快照（受影响口径条目 + 索引），
 * 合并步骤逐条记录进度；任一步骤失败 → 已写入条目全部撤销、索引还原到合并前状态、
 * 口径包状态回退为 REVIEWED，并生成 {@link RollbackReport} 写入运行摘要与审计记录
 * （失败步骤 + 回滚条目数 + 回滚后版本）。</p>
 *
 * <p>失败一律抛 AppException（本事务自定义码 E_ROLLBACK_001），回滚报告可通过
 * {@link #getRollbackReport()} 与落盘审计 / 摘要反查。</p>
 */
public final class MergeTransaction {

    /** 一条待合并写入的口径（合并后统一落到目标版本，状态 DRAFT 待复核）。 */
    public static final class MergeItem {
        private final String indicatorCode;
        private final String indicatorName;
        private final ConditionTree numerator;
        private final ConditionTree denominator;
        private final List<String> deptCodes;
        private final String effectiveFrom;
        private final String effectiveTo;

        public MergeItem(String indicatorCode, String indicatorName,
                         ConditionTree numerator, ConditionTree denominator,
                         List<String> deptCodes, String effectiveFrom, String effectiveTo) {
            this.indicatorCode = indicatorCode == null ? "" : indicatorCode;
            this.indicatorName = indicatorName == null ? "" : indicatorName;
            this.numerator = numerator;
            this.denominator = denominator;
            this.deptCodes = deptCodes == null ? List.of() : List.copyOf(deptCodes);
            this.effectiveFrom = effectiveFrom == null ? "" : effectiveFrom;
            this.effectiveTo = effectiveTo == null ? "" : effectiveTo;
        }

        public String getIndicatorCode() { return indicatorCode; }
        public String getIndicatorName() { return indicatorName; }
        public ConditionTree getNumerator() { return numerator; }
        public ConditionTree getDenominator() { return denominator; }
        public List<String> getDeptCodes() { return deptCodes; }
        public String getEffectiveFrom() { return effectiveFrom; }
        public String getEffectiveTo() { return effectiveTo; }
    }

    private final Path root;
    private final String packageVersion;
    private final String targetVersion;
    private final Path defsFile, indexFile, stateFile, auditFile, summaryFile;

    private String snapshotDefs, snapshotIndex, snapshotState;
    private String baselineSignature;
    private final List<String> steps = new ArrayList<>();
    private RollbackReport rollbackReport;
    private boolean committed = false;

    public MergeTransaction(Path root, String packageVersion, String targetVersion) {
        this.root = root;
        this.packageVersion = packageVersion == null ? "" : packageVersion;
        this.targetVersion = targetVersion == null ? "" : targetVersion;
        this.defsFile = root.resolve("definitions.json");
        this.indexFile = root.resolve("index.json");
        this.stateFile = root.resolve("package_state.json");
        this.auditFile = root.resolve("audit.json");
        this.summaryFile = root.resolve("summary.json");
        AtomicFiles.ensureDir(root);
        initEmptyFiles();
    }

    /** 初始化合并前的库状态：口径包处于 REVIEWED，审计与摘要为空。 */
    private void initEmptyFiles() {
        if (!AtomicFiles.exists(defsFile)) write(defsFile, emptyArray("definitions"));
        if (!AtomicFiles.exists(indexFile)) write(indexFile, emptyArray("entries"));
        if (!AtomicFiles.exists(stateFile)) write(stateFile, stateNode(PackageStatus.REVIEWED));
        if (!AtomicFiles.exists(auditFile)) write(auditFile, emptyArray("records"));
        if (!AtomicFiles.exists(summaryFile)) write(summaryFile, emptyArray("runs"));
    }

    /** 放入合并前的本院口径（已有版本），重建索引并保持状态 REVIEWED。 */
    public void seed(List<MetricDefinition> existing) {
        ObjectNode defs = emptyArray("definitions");
        ObjectNode idx = emptyArray("entries");
        if (existing != null) {
            for (MetricDefinition d : existing) {
                ((ArrayNode) defs.path("definitions")).add(d.toJson());
                ObjectNode e = ((ArrayNode) idx.path("entries")).addObject();
                e.put("key", d.key());
                e.put("indicator_code", d.getIndicatorCode());
                e.put("definition_version", d.getDefinitionVersion());
            }
        }
        write(defsFile, defs);
        write(indexFile, idx);
        write(stateFile, stateNode(PackageStatus.REVIEWED));
        write(auditFile, emptyArray("records"));
        write(summaryFile, emptyArray("runs"));
        this.committed = false;
        this.rollbackReport = null;
        this.steps.clear();
    }

    /** 合并前生效版本集合签名（排序后的 key 串），即「回滚后版本」。 */
    public String baselineSignature() {
        List<String> keys = new ArrayList<>();
        JsonNode idx = readObj(indexFile, "entries");
        for (JsonNode e : idx.path("entries")) keys.add(e.path("key").asText());
        Collections.sort(keys);
        return keys.isEmpty() ? "(空库基线)" : String.join("|", keys);
    }

    /** 不注入失败的完整合并。 */
    public void merge(List<MergeItem> items) { merge(items, -1); }

    /**
     * 执行合并事务。
     *
     * @param items      待写入口径条目
     * @param failAtStep 注入失败的步骤下标（0 基，&lt;0 表示不失败）；该步骤写入前触发失败
     */
    public void merge(List<MergeItem> items, int failAtStep) {
        List<MergeItem> list = items == null ? List.of() : List.copyOf(items);
        // 1) 合并前快照：受影响口径条目 + 索引 + 状态
        snapshotDefs = AtomicFiles.readString(defsFile);
        snapshotIndex = AtomicFiles.readString(indexFile);
        snapshotState = AtomicFiles.readString(stateFile);
        baselineSignature = baselineSignature();
        steps.clear();
        steps.add("BEGIN 记录合并前快照（口径条目 + 索引），基线版本=" + baselineSignature);

        // 2) 口径包状态乐观推进到 MERGED（失败时回退为 REVIEWED）
        write(stateFile, stateNode(PackageStatus.MERGED));
        steps.add("STATE->MERGED");

        int written = 0;
        for (int i = 0; i < list.size(); i++) {
            MergeItem it = list.get(i);
            if (i == failAtStep) {
                rollbackAndThrow("STEP-" + (i + 1) + " 写入条目 " + it.getIndicatorCode(), written);
            }
            writeOne(it);
            written++;
            steps.add("STEP-" + (i + 1) + " 写入 " + it.getIndicatorCode() + " -> " + targetVersion);
        }

        // 3) 全部成功：提交，写运行摘要与审计
        ObjectNode run = JsonSupport.object();
        run.put("result", "MERGED");
        run.put("package_version", packageVersion);
        run.put("target_version", targetVersion);
        run.put("written_entries", list.size());
        appendRun(run);
        appendAudit("合并成功：写入 " + list.size() + " 条 DRAFT 版本，口径包状态 MERGED；目标版本 " + targetVersion);
        committed = true;
    }

    /** 逐条写入一条新 DRAFT 口径版本，并登记索引（带来源包版本）。 */
    private void writeOne(MergeItem it) {
        MetricDefinition md = MetricDefinition.builder(it.getIndicatorCode(), targetVersion)
                .title(it.getIndicatorName())
                .numerator(it.getNumerator())
                .denominator(it.getDenominator())
                .deptCodes(it.getDeptCodes())
                .effectiveFrom(it.getEffectiveFrom())
                .effectiveTo(it.getEffectiveTo())
                .status(MetricStatus.DRAFT)
                .dictionaryVersion("source:" + packageVersion)
                .build();
        ObjectNode defJson = md.toJson();
        defJson.put("source_package_version", packageVersion);

        ObjectNode defs = readObj(defsFile, "definitions");
        ((ArrayNode) defs.path("definitions")).add(defJson);
        write(defsFile, defs);

        ObjectNode idx = readObj(indexFile, "entries");
        ObjectNode e = ((ArrayNode) idx.path("entries")).addObject();
        e.put("key", md.key());
        e.put("indicator_code", md.getIndicatorCode());
        e.put("definition_version", md.getDefinitionVersion());
        write(indexFile, idx);
    }

    /** 回滚：撤销全部已写入条目、还原索引、状态回退 REVIEWED，并落审计与运行摘要。 */
    private void rollbackAndThrow(String failedStep, int written) {
        // 用合并前快照原文整体覆盖，保证口径库逐项一致（字节级）
        AtomicFiles.writeString(defsFile, snapshotDefs);
        AtomicFiles.writeString(indexFile, snapshotIndex);
        // 状态显式回退为 REVIEWED（与合并前 stateNode 完全一致，字节级一致）
        write(stateFile, stateNode(PackageStatus.REVIEWED));

        String now = Instant.now().toString();
        String auditLine = "回滚审计：失败步骤=" + failedStep
                + "；回滚条目数=" + written
                + "；回滚后版本=" + baselineSignature
                + "；时间=" + now;
        String summaryText = "合并事务回滚运行摘要：失败步骤=" + failedStep
                + "；回滚条目数=" + written
                + "；回滚后版本=" + baselineSignature
                + "；口径包状态回退为 REVIEWED";

        rollbackReport = RollbackReport.builder()
                .packageVersion(packageVersion)
                .targetVersion(targetVersion)
                .failedStep(failedStep)
                .rolledBackEntryCount(written)
                .versionAfterRollback(baselineSignature)
                .rolledBackAt(now)
                .addAuditRecord(auditLine)
                .runSummary(summaryText)
                .build();

        ObjectNode run = JsonSupport.object();
        run.put("result", "ROLLED_BACK");
        run.put("failed_step", failedStep);
        run.put("rolled_back_entry_count", written);
        run.put("version_after_rollback", baselineSignature);
        run.put("package_version", packageVersion);
        run.put("target_version", targetVersion);
        appendRun(run);
        appendAudit(auditLine);
        steps.add("ROLLBACK " + failedStep + "，撤销 " + written + " 条写入，状态回退 REVIEWED");

        throw new AppException("E_ROLLBACK_001",
                "合并事务在 " + failedStep + " 失败，已整体回滚：撤销 " + written
                        + " 条写入、索引还原到合并前状态、口径包状态回退为 REVIEWED")
                .with("failed_step", failedStep)
                .with("rolled_back_entry_count", String.valueOf(written))
                .with("version_after_rollback", baselineSignature);
    }

    // ---- 观测接口 ----

    public RollbackReport getRollbackReport() { return rollbackReport; }
    public List<String> getSteps() { return List.copyOf(steps); }
    public boolean isCommitted() { return committed; }

    public PackageStatus currentStatus() {
        JsonNode n = JsonSupport.parse(AtomicFiles.readString(stateFile));
        return PackageStatus.of(n.path("status").asText("IMPORTED"));
    }

    /** 库文件原文，供测试逐项比对「回滚后与合并前一致」。 */
    public String readDefinitionsRaw() { return AtomicFiles.readString(defsFile); }
    public String readIndexRaw() { return AtomicFiles.readString(indexFile); }
    public String readStateRaw() { return AtomicFiles.readString(stateFile); }
    public String readAuditRaw() { return AtomicFiles.readString(auditFile); }
    public String readSummaryRaw() { return AtomicFiles.readString(summaryFile); }

    // ---- 内部工具 ----

    private ObjectNode stateNode(PackageStatus status) {
        ObjectNode o = JsonSupport.object();
        o.put("package_version", packageVersion);
        o.put("status", status.name());
        return o;
    }

    private ObjectNode emptyArray(String key) {
        ObjectNode o = JsonSupport.object();
        o.putArray(key);
        return o;
    }

    private ObjectNode readObj(Path path, String arrayKey) {
        if (!AtomicFiles.exists(path)) return emptyArray(arrayKey);
        JsonNode n = JsonSupport.parse(AtomicFiles.readString(path));
        if (n == null || !n.isObject() || !n.path(arrayKey).isArray()) {
            throw new AppException("E_STORE_001", "合并事务库文件损坏：" + path + "（缺少 " + arrayKey + " 数组）");
        }
        return (ObjectNode) n;
    }

    private void appendAudit(String line) {
        ObjectNode o = readObj(auditFile, "records");
        ((ArrayNode) o.path("records")).add(line);
        write(auditFile, o);
    }

    private void appendRun(ObjectNode run) {
        ObjectNode o = readObj(summaryFile, "runs");
        ((ArrayNode) o.path("runs")).add(run);
        write(summaryFile, o);
    }

    private void write(Path path, JsonNode node) {
        AtomicFiles.writeString(path, JsonSupport.pretty(node));
    }
}
