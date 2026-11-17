package com.infection.metric.metriclib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.AtomicFiles;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 文件型口径库：按年度分区落盘 root/&lt;年度&gt;/&lt;indicator_code&gt;/&lt;definition_version&gt;.json，
 * 顶层 index.json 按年度列出全部口径版本。写入一律走 AtomicFiles 原子写；
 * 同主键内容相同幂等、内容不同显式冲突；每次状态跃迁或内容变更前把旧版本存为快照并保留最近 N 份。
 */
public final class MetricStore {

    /** 快照时间戳：固定宽度纳秒，保证文件名字典序即时间序。含 ISO 日期与 T/Z。 */
    private static final DateTimeFormatter SNAPSHOT_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final int DEFAULT_MAX_SNAPSHOTS = 10;
    private static final Pattern YEAR = Pattern.compile("(\\d{4})");

    private final Path root;
    private final int maxSnapshots;
    private long snapshotSeq = 0L;

    public MetricStore(Path root) { this(root, DEFAULT_MAX_SNAPSHOTS); }

    public MetricStore(Path root, int maxSnapshots) {
        if (maxSnapshots < 1) {
            throw ErrorCodes.error(ErrorCodes.E_STORE_001)
                    .with("reason", "快照保留份数必须为正整数，实际 " + maxSnapshots);
        }
        this.root = root;
        this.maxSnapshots = maxSnapshots;
        AtomicFiles.ensureDir(root);
        Path idx = root.resolve("index.json");
        if (!AtomicFiles.exists(idx)) {
            ObjectNode empty = JsonSupport.object();
            empty.put("schema_version", 1);
            empty.putObject("years");
            AtomicFiles.writeString(idx, JsonSupport.pretty(empty));
        } else {
            readIndex();
        }
    }

    /**
     * 幂等落盘：同主键已存在且内容相同 → 不产生第二份文件与第二条索引记录；
     * 内容不同 → 抛 E_CONFLICT_001，绝不静默覆盖。新主键先写定义文件、成功后再更新索引。
     */
    public MetricDefinition save(MetricDefinition def) {
        ObjectNode idx = readIndex();
        JsonNode entry = findEntry(idx, def.getIndicatorCode(), def.getDefinitionVersion());
        if (entry != null) {
            Path disk = root.resolve(entry.path("path").asText());
            if (AtomicFiles.exists(disk)) {
                MetricDefinition existing = MetricDefinition.fromJson(JsonSupport.read(disk));
                if (existing.sameContent(def)) {
                    return existing;
                }
                throw ErrorCodes.error(ErrorCodes.E_CONFLICT_001, "key", def.key())
                        .with("磁盘已有标题", existing.getTitle())
                        .with("本次提交标题", def.getTitle())
                        .hinting("同 (indicator_code, definition_version) 内容不同不覆盖，请改用新版本号或先核对差异");
            }
        }
        writeDefinition(def);
        upsertIndex(def);
        return def;
    }

    /** 状态跃迁落盘：先经状态机校验，再把旧版本存为快照，最后覆盖写并更新索引。 */
    public MetricDefinition transition(String code, String version, MetricStatus target, String actor) {
        MetricDefinition cur = load(code, version);
        cur.getStatus().requireTransition(target);
        String approver = cur.getApprover();
        String approvedAt = cur.getApprovedAt();
        if (target == MetricStatus.PUBLISHED) {
            if (actor == null || actor.isBlank()) {
                throw ErrorCodes.error(ErrorCodes.E_AUDIT_001, "reason", "发布 PUBLISHED 缺少审批人 approver");
            }
            approver = actor;
            approvedAt = Instant.now().toString();
        }
        MetricDefinition next = cur.toBuilder()
                .status(target).approver(approver).approvedAt(approvedAt).build();
        return persistChange(cur, next);
    }

    /** 内容变更落盘：已发布 / 已停用版本不可直接改，变更前存旧版本快照。 */
    public MetricDefinition updateContent(MetricDefinition replacement) {
        MetricDefinition cur = load(replacement.getIndicatorCode(), replacement.getDefinitionVersion());
        if (cur.getStatus() == MetricStatus.PUBLISHED || cur.getStatus() == MetricStatus.RETIRED) {
            throw ErrorCodes.error(ErrorCodes.E_STATE_001,
                            "from", cur.getStatus().name(), "to", "内容修改")
                    .hinting("已发布 / 已停用版本不可直接改内容，请新建草稿版本");
        }
        if (replacement.getStatus() == MetricStatus.PUBLISHED) {
            throw ErrorCodes.error(ErrorCodes.E_PUBLISH_001,
                    "reason", "不得借 updateContent 直接发布，请走 transition");
        }
        return persistChange(cur, replacement);
    }

    public MetricDefinition load(String code, String version) {
        ObjectNode idx = readIndex();
        JsonNode entry = findEntry(idx, code, version);
        if (entry == null) {
            throw ErrorCodes.error(ErrorCodes.E_GRAPH_004, "id", code + "|" + version)
                    .hinting("索引中没有该口径版本，请先 save");
        }
        Path disk = root.resolve(entry.path("path").asText());
        if (!AtomicFiles.exists(disk)) {
            throw ErrorCodes.error(ErrorCodes.E_STORE_001).with("path", disk.toString())
                    .hinting("索引指向的定义文件缺失，需恢复或重建年度分区");
        }
        return MetricDefinition.fromJson(JsonSupport.read(disk));
    }

    /** 升序年度分区名。 */
    public List<String> listYears() {
        ObjectNode idx = readIndex();
        List<String> out = new ArrayList<>();
        idx.path("years").fieldNames().forEachRemaining(out::add);
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    /** 列出某年度下的全部口径定义（按联合主键升序）。 */
    public List<MetricDefinition> listByYear(String year) {
        ObjectNode idx = readIndex();
        JsonNode arr = idx.path("years").path(year);
        List<MetricDefinition> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode e : arr) {
                Path disk = root.resolve(e.path("path").asText());
                if (AtomicFiles.exists(disk)) {
                    out.add(MetricDefinition.fromJson(JsonSupport.read(disk)));
                }
            }
        }
        out.sort(Comparator.comparing(MetricDefinition::key));
        return List.copyOf(out);
    }

    /** 返回经校验的索引；缺失 / 损坏 / 结构不合法一律抛 E_STORE_001。 */
    public ObjectNode index() { return readIndex(); }

    private MetricDefinition persistChange(MetricDefinition before, MetricDefinition after) {
        snapshot(before);
        writeDefinition(after);
        upsertIndex(after);
        return after;
    }

    private void snapshot(MetricDefinition before) {
        Path dir = defDir(before);
        AtomicFiles.ensureDir(dir);
        String name = String.format("snapshot-%s-%06d.json",
                SNAPSHOT_TS.format(Instant.now()), ++snapshotSeq);
        AtomicFiles.writeString(dir.resolve(name), JsonSupport.pretty(before.toJson()));
        pruneSnapshots(dir);
    }

    private void pruneSnapshots(Path dir) {
        List<Path> snaps = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("snapshot-") && n.endsWith(".json");
            }).forEach(snaps::add);
        } catch (IOException e) {
            throw storeFailure("列举版本快照失败：" + dir, e);
        }
        snaps.sort(Comparator.comparing(p -> p.getFileName().toString()));
        while (snaps.size() > maxSnapshots) {
            Path oldest = snaps.remove(0);
            try {
                Files.deleteIfExists(oldest);
            } catch (IOException e) {
                throw storeFailure("淘汰旧快照失败：" + oldest, e);
            }
        }
    }

    private void writeDefinition(MetricDefinition def) {
        AtomicFiles.writeString(defPath(def), JsonSupport.pretty(def.toJson()));
    }

    private void upsertIndex(MetricDefinition def) {
        ObjectNode idx = readIndex();
        ObjectNode years = (ObjectNode) idx.path("years");
        String year = yearOf(def);
        ArrayNode arr;
        JsonNode existing = years.get(year);
        if (existing != null && existing.isArray()) {
            arr = (ArrayNode) existing;
        } else {
            arr = years.putArray(year);
        }
        ArrayNode cleaned = JsonSupport.array();
        for (JsonNode e : arr) {
            if (!(def.getIndicatorCode().equals(e.path("indicator_code").asText())
                    && def.getDefinitionVersion().equals(e.path("definition_version").asText()))) {
                cleaned.add(e);
            }
        }
        ObjectNode entry = JsonSupport.object();
        entry.put("indicator_code", def.getIndicatorCode());
        entry.put("definition_version", def.getDefinitionVersion());
        entry.put("status", def.getStatus().name());
        entry.put("year", year);
        entry.put("path", relativePath(def));
        cleaned.add(entry);
        years.set(year, cleaned);
        AtomicFiles.writeString(root.resolve("index.json"), JsonSupport.pretty(idx));
    }

    private ObjectNode readIndex() {
        Path idx = root.resolve("index.json");
        if (!Files.exists(idx)) {
            throw ErrorCodes.error(ErrorCodes.E_STORE_001).with("path", idx.toString())
                    .hinting("index.json 缺失，需恢复索引或重建年度分区，不静默返回空库");
        }
        String text;
        try {
            text = Files.readString(idx, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw storeFailure("读取 index.json 失败：" + idx, e);
        }
        JsonNode n;
        try {
            n = JsonSupport.mapper().readTree(text);
        } catch (IOException e) {
            throw ErrorCodes.error(ErrorCodes.E_STORE_001).with("path", idx.toString())
                    .with("detail", "index.json 不是合法 JSON");
        }
        if (n == null || !n.isObject() || !n.path("years").isObject()) {
            throw ErrorCodes.error(ErrorCodes.E_STORE_001).with("path", idx.toString())
                    .with("detail", "index.json 结构损坏：缺少 years 对象");
        }
        return (ObjectNode) n;
    }

    private JsonNode findEntry(ObjectNode idx, String code, String version) {
        JsonNode years = idx.path("years");
        Iterator<Map.Entry<String, JsonNode>> it = years.fields();
        while (it.hasNext()) {
            JsonNode arr = it.next().getValue();
            if (!arr.isArray()) continue;
            for (JsonNode e : arr) {
                if (code.equals(e.path("indicator_code").asText())
                        && version.equals(e.path("definition_version").asText())) {
                    return e;
                }
            }
        }
        return null;
    }

    private String yearOf(MetricDefinition def) {
        String from = def.getEffectiveFrom();
        if (from != null) {
            Matcher m = YEAR.matcher(from);
            if (m.find()) return m.group(1);
        }
        Matcher m2 = YEAR.matcher(def.getDefinitionVersion());
        if (m2.find()) return m2.group(1);
        return String.valueOf(Year.now().getValue());
    }

    private Path defDir(MetricDefinition def) {
        return root.resolve(yearOf(def)).resolve(safe(def.getIndicatorCode()));
    }

    private Path defPath(MetricDefinition def) {
        return defDir(def).resolve(safe(def.getDefinitionVersion()) + ".json");
    }

    private String relativePath(MetricDefinition def) {
        return yearOf(def) + "/" + safe(def.getIndicatorCode()) + "/"
                + safe(def.getDefinitionVersion()) + ".json";
    }

    private static String safe(String raw) {
        return raw.replace('/', '_').replace('\\', '_').replace("..", "_");
    }

    private static AppException storeFailure(String message, Throwable cause) {
        return new AppException("E_STORE_002", message, cause);
    }
}
