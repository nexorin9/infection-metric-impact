package com.infection.metric.pkg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * 口径包解析结果的可序列化中间结构：一次解析只承载一个包版本。
 *
 * <p>保留全部条目（含不完整条目，绝不静默丢弃），并对外提供总条数、
 * 不完整条数 / 清单、完整条目清单，供后续三清单分类、逐条确认与合并任务使用。
 * {@link #toJson()} / {@link #fromJson(JsonNode)} 支持落盘与跨任务传递。</p>
 */
public final class ParsedPackage {

    private final String packageVersion;
    private final String format;
    private final String source;
    private final List<PackageEntry> entries;

    public ParsedPackage(String packageVersion, String format, String source, List<PackageEntry> entries) {
        this.packageVersion = packageVersion == null || packageVersion.isBlank() ? "unknown" : packageVersion;
        this.format = format == null ? "" : format;
        this.source = source == null ? "" : source;
        this.entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public String getPackageVersion() { return packageVersion; }
    public String getFormat() { return format; }
    public String getSource() { return source; }
    public List<PackageEntry> getEntries() { return entries; }

    public int totalCount() { return entries.size(); }

    /** 不完整条目数：缺任一必需字段即计入，不静默丢弃。 */
    public int incompleteCount() {
        int n = 0;
        for (PackageEntry e : entries) if (!e.isComplete()) n++;
        return n;
    }

    public int completeCount() { return totalCount() - incompleteCount(); }

    public List<PackageEntry> incompleteEntries() {
        List<PackageEntry> out = new ArrayList<>();
        for (PackageEntry e : entries) if (!e.isComplete()) out.add(e);
        return List.copyOf(out);
    }

    public List<PackageEntry> completeEntries() {
        List<PackageEntry> out = new ArrayList<>();
        for (PackageEntry e : entries) if (e.isComplete()) out.add(e);
        return List.copyOf(out);
    }

    public PackageEntry findByEntryNo(String entryNo) {
        for (PackageEntry e : entries) if (e.getEntryNo().equals(entryNo)) return e;
        return null;
    }

    /** 中间结构序列化：含包版本、格式、来源、计数与全部条目。 */
    public ObjectNode toJson() {
        ObjectNode o = JsonSupport.object();
        o.put("schema_version", 1);
        o.put("package_version", packageVersion);
        o.put("format", format);
        o.put("source", source);
        o.put("total_count", totalCount());
        o.put("incomplete_count", incompleteCount());
        o.put("complete_count", completeCount());
        ArrayNode arr = o.putArray("entries");
        for (PackageEntry e : entries) arr.add(e.toJson());
        return o;
    }

    public String toJsonText() { return JsonSupport.pretty(toJson()); }

    public static ParsedPackage fromJson(JsonNode n) {
        List<PackageEntry> entries = new ArrayList<>();
        JsonNode arr = n.path("entries");
        if (arr.isArray()) for (JsonNode e : arr) entries.add(PackageEntry.fromJson(e));
        return new ParsedPackage(n.path("package_version").asText("unknown"),
                n.path("format").asText(""), n.path("source").asText(""), entries);
    }

    public static ParsedPackage fromJson(String text) { return fromJson(JsonSupport.parse(text)); }
}
