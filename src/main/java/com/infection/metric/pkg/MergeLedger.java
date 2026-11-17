package com.infection.metric.pkg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AtomicFiles;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.metriclib.MetricDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 口径包合并台账（文件型，落盘路径由调用方注入，测试用 @TempDir 隔离，不污染项目 data/）。
 *
 * <p>三份落盘结构：</p>
 * <ul>
 *   <li>{@code receipts.json}：全部合并回执，可按包版本 / 幂等键反查；</li>
 *   <li>{@code merge_log.json}：已合并记录（含幂等键），重复导入双保险之一；</li>
 *   <li>{@code local_library.json}：合并结果本地落库（本院口径新增版本，状态 DRAFT 待复核，记来源包版本）。</li>
 * </ul>
 * 写入一律走 AtomicFiles 原子写；同一 (package_version, target_version) 重复登记不产生第二条记录。
 */
public final class MergeLedger {

    private final Path root;
    private final Path receiptsFile;
    private final Path logFile;
    private final Path localFile;

    public MergeLedger(Path root) {
        this.root = root;
        this.receiptsFile = root.resolve("receipts.json");
        this.logFile = root.resolve("merge_log.json");
        this.localFile = root.resolve("local_library.json");
        AtomicFiles.ensureDir(root);
        initArrayFile(receiptsFile, "receipts");
        initArrayFile(logFile, "records");
        initArrayFile(localFile, "definitions");
    }

    public Path getRoot() { return root; }

    // ---- 重复导入保护（已合并记录 + 幂等键 双保险） ----

    /** 已合并记录：按 (包版本, 目标版本) 判定。 */
    public boolean hasMerged(String packageVersion, String targetVersion) {
        return findLog(packageVersion, targetVersion, null) != null;
    }

    /** 幂等键是否已登记（双保险之二）。 */
    public boolean hasIdempotencyKey(String key) {
        if (key == null || key.isBlank()) return false;
        return findLog(null, null, key) != null;
    }

    /** 已合并时间（供 E_PKG_003「该包已于 X 合并至版本 Y」文案）。 */
    public String mergedAt(String packageVersion, String targetVersion) {
        JsonNode rec = findLog(packageVersion, targetVersion, null);
        return rec == null ? "" : rec.path("merged_at").asText("");
    }

    private JsonNode findLog(String pv, String tv, String key) {
        JsonNode arr = read().path("records");
        if (!arr.isArray()) return null;
        for (JsonNode r : arr) {
            if (key != null && key.equals(r.path("idempotency_key").asText())) return r;
            if (pv != null && tv != null
                    && pv.equals(r.path("package_version").asText())
                    && tv.equals(r.path("target_version").asText())) return r;
        }
        return null;
    }

    // ---- 回执落库与反查 ----

    /** 登记一条合并回执 + 一条已合并记录（含幂等键）；同包同目标版本重复登记不产生第二条。 */
    public void record(MergeReceipt receipt) {
        String pv = receipt.getPackageVersion();
        String tv = receipt.getTargetVersion();
        if (hasMerged(pv, tv)) return; // 幂等：已有记录不再追加

        ObjectNode receipts = readReceipts();
        ((ArrayNode) receipts.path("receipts")).add(receipt.toJson());
        write(receiptsFile, receipts);

        ObjectNode log = read();
        ObjectNode rec = JsonSupport.object();
        rec.put("package_version", pv);
        rec.put("target_version", tv);
        String key = receipt.getIdempotencyKey().isBlank()
                ? Hashes.sha256Of(pv, tv) : receipt.getIdempotencyKey();
        rec.put("idempotency_key", key);
        rec.put("merged_at", receipt.getMergedAt());
        ((ArrayNode) log.path("records")).add(rec);
        write(logFile, log);
    }

    /** 按包版本反查最近一条合并回执。 */
    public MergeReceipt findReceipt(String packageVersion) {
        JsonNode arr = readReceipts().path("receipts");
        if (arr.isArray()) {
            for (int i = arr.size() - 1; i >= 0; i--) {
                JsonNode r = arr.get(i);
                if (packageVersion.equals(r.path("package_version").asText())) {
                    return MergeReceipt.fromJson(r);
                }
            }
        }
        return null;
    }

    /** 按 (包版本, 目标版本) 反查回执。 */
    public MergeReceipt findReceipt(String packageVersion, String targetVersion) {
        JsonNode arr = readReceipts().path("receipts");
        if (arr.isArray()) {
            for (JsonNode r : arr) {
                if (packageVersion.equals(r.path("package_version").asText())
                        && targetVersion.equals(r.path("target_version").asText())) {
                    return MergeReceipt.fromJson(r);
                }
            }
        }
        return null;
    }

    /** 按幂等键反查回执（双保险反查入口）。 */
    public MergeReceipt findByIdempotencyKey(String key) {
        JsonNode arr = readReceipts().path("receipts");
        if (arr.isArray()) {
            for (JsonNode r : arr) {
                if (key.equals(r.path("idempotency_key").asText())) {
                    return MergeReceipt.fromJson(r);
                }
            }
        }
        return null;
    }

    public List<MergeReceipt> allReceipts() {
        List<MergeReceipt> out = new ArrayList<>();
        JsonNode arr = readReceipts().path("receipts");
        if (arr.isArray()) for (JsonNode r : arr) out.add(MergeReceipt.fromJson(r));
        return List.copyOf(out);
    }

    /** 已合并记录条数（重复导入保护测试据此断言不产生第二条）。 */
    public int mergedRecordCount() {
        JsonNode arr = read().path("records");
        return arr.isArray() ? arr.size() : 0;
    }

    // ---- 合并结果本地落库（DRAFT 待复核 + 记来源包版本） ----

    /**
     * 把合并后的本院口径新版本落库：每条写成 {@code MetricDefinition.toJson()} +
     * {@code source_package_version}，按 (code|version) 幂等去重。
     */
    public void saveLocalDefinitions(List<MetricDefinition> defs, String sourcePackageVersion) {
        ObjectNode store = readLocal();
        ArrayNode arr = (ArrayNode) store.path("definitions");
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (JsonNode d : arr) keys.add(d.path("indicator_code").asText() + "|" + d.path("definition_version").asText());
        for (MetricDefinition def : defs) {
            String k = def.key();
            if (keys.contains(k)) continue; // 幂等：不产生第二份
            ObjectNode o = def.toJson();
            o.put("source_package_version", sourcePackageVersion);
            arr.add(o);
            keys.add(k);
        }
        write(localFile, store);
    }

    public List<JsonNode> localDefinitions() {
        List<JsonNode> out = new ArrayList<>();
        JsonNode arr = readLocal().path("definitions");
        if (arr.isArray()) for (JsonNode d : arr) out.add(d);
        return List.copyOf(out);
    }

    /** 反查本地已落库的某口径版本（带来源包版本标记）。 */
    public JsonNode findLocalDefinition(String code, String version) {
        String k = code + "|" + version;
        for (JsonNode d : localDefinitions()) {
            if (k.equals(d.path("indicator_code").asText() + "|" + d.path("definition_version").asText())) {
                return d;
            }
        }
        return null;
    }

    // ---- 内部读写 ----

    private ObjectNode read() { return readObject(logFile, "records"); }
    private ObjectNode readReceipts() { return readObject(receiptsFile, "receipts"); }
    private ObjectNode readLocal() { return readObject(localFile, "definitions"); }

    private ObjectNode readObject(Path path, String arrayKey) {
        if (!AtomicFiles.exists(path)) {
            ObjectNode o = JsonSupport.object();
            o.putArray(arrayKey);
            return o;
        }
        JsonNode n = JsonSupport.parse(AtomicFiles.readString(path));
        if (n == null || !n.isObject() || !n.path(arrayKey).isArray()) {
            throw new com.infection.metric.common.AppException("E_STORE_001",
                    "合并台账文件损坏：" + path + "（缺少 " + arrayKey + " 数组）");
        }
        return (ObjectNode) n;
    }

    private void write(Path path, ObjectNode node) {
        AtomicFiles.writeString(path, JsonSupport.pretty(node));
    }

    private void initArrayFile(Path path, String arrayKey) {
        if (!AtomicFiles.exists(path)) {
            ObjectNode o = JsonSupport.object();
            o.put("schema_version", 1);
            o.putArray(arrayKey);
            write(path, o);
        } else {
            readObject(path, arrayKey); // 损坏则显式失败，不静默重建
        }
    }
}
