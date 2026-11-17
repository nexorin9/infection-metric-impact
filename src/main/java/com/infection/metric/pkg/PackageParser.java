package com.infection.metric.pkg;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.AtomicFiles;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 上级口径包解析器：格式 × 版本分派（源产品 FhirResourceParser 的分派与明确报错模式迁移）。
 *
 * <p>支持 {@code csv} / {@code json} 两种格式；按扩展名与内容头双重分派到对应解析器；
 * 单次调用只解析「一个包版本」。不支持的格式（xlsx 等）明确报「不支持的格式」，
 * 列出当前支持格式并给出可执行的转换提示（用 Excel 另存为 CSV UTF-8），
 * 用 {@link ErrorCodes#E_PKG_001}。</p>
 *
 * <p>CSV 走 {@link BufferedReader}（含表头），JSON 走 Jackson（经
 * {@link JsonSupport#parse(String)}）。缺必需字段的条目标记为不完整并计数，
 * 不静默丢弃。解析结果为可序列化的 {@link ParsedPackage} 中间结构。</p>
 */
public final class PackageParser {

    /** 当前支持格式清单，报错时原样列出。 */
    public static final List<String> SUPPORTED_FORMATS = List.of("csv", "json");

    /** CSV 表头别名 → 标准字段名（同时容纳英文 snake_case 与中文业务名）。 */
    private static final Map<String, String> HEADER_ALIASES = buildAliases();

    private static Map<String, String> buildAliases() {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "entry_no", "entry_no", "条目编号", "条目号", "编号");
        put(m, "indicator_code", "indicator_code", "指标编码", "指标代码", "编码");
        put(m, "indicator_name", "indicator_name", "指标名称", "名称");
        put(m, "definition", "definition", "口径定义", "定义");
        put(m, "numerator", "numerator", "分子", "分子口径", "分子定义");
        put(m, "denominator", "denominator", "分母", "分母口径", "分母定义");
        put(m, "dept_codes", "dept_codes", "适用科室", "科室", "科室代码");
        put(m, "effective_from", "effective_from", "生效期", "生效起始", "生效日期", "生效开始");
        put(m, "effective_to", "effective_to", "生效结束", "失效期", "生效截止");
        put(m, "package_version", "package_version", "包版本", "口径包版本");
        return m;
    }

    private static void put(Map<String, String> m, String canonical, String... aliases) {
        for (String a : aliases) m.put(normalizeHeader(a), canonical);
    }

    private static String normalizeHeader(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replace(" ", "")
                .replace("_", "").replace("-", "");
    }

    /** 解析声明格式：有扩展名取扩展名；无点时仅当整串恰为 csv / json 显式标识才认。 */
    private static String declaredFormat(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) return null;
        String s = sourceName.trim();
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            String ext = s.substring(dot + 1).toLowerCase(Locale.ROOT);
            return ext.isEmpty() ? null : ext;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        return SUPPORTED_FORMATS.contains(lower) ? lower : null;
    }

    /** 内容头嗅探：JSON 以 { / [ 起头；含逗号的文本行视为 CSV。 */
    private static String sniffFormat(String content) {
        if (content == null) return null;
        String t = content.trim();
        if (t.isEmpty()) return null;
        char c = t.charAt(0);
        if (c == '{' || c == '[') return "json";
        if (t.contains(",")) return "csv";
        return null;
    }

    /** 解析一个具体文件（扩展名 + 内容头分派），包版本从内容推断。 */
    public ParsedPackage parse(Path file) {
        return parse(file, null);
    }

    /** 解析一个具体文件，显式给定包版本。 */
    public ParsedPackage parse(Path file, String packageVersion) {
        String content = AtomicFiles.readString(file);
        return parseText(content, file.getFileName().toString(), packageVersion);
    }

    /** 解析文本内容，sourceName 可为带扩展名的文件名，也可直接是 csv / json 格式标识。 */
    public ParsedPackage parseText(String content, String sourceName) {
        return parseText(content, sourceName, null);
    }

    /**
     * 核心入口：一次只解析一个包版本。
     *
     * @param content        包内容文本
     * @param sourceName     文件名或格式标识（用于扩展名分派）
     * @param packageVersion 显式包版本；为 null 时从内容（CSV 的 package_version 列 /
     *                       JSON 的 package_version 字段）推断
     */
    public ParsedPackage parseText(String content, String sourceName, String packageVersion) {
        String declared = declaredFormat(sourceName);
        String sniffed = sniffFormat(content);

        String format;
        if (declared != null && !SUPPORTED_FORMATS.contains(declared)) {
            // 明确的不支持扩展名（xlsx / xls / xml 等）优先级最高，直接报错，不受内容头影响
            throw unsupported(declared);
        }
        if (declared != null) {
            format = declared;
            // 内容头与扩展名冲突且内容头也指向另一种支持格式时，以内容头为准
            if (sniffed != null && SUPPORTED_FORMATS.contains(sniffed) && !sniffed.equals(format)) {
                format = sniffed;
            }
        } else {
            format = sniffed; // 无声明格式时完全交给内容头分派
        }
        if (format == null || !SUPPORTED_FORMATS.contains(format)) {
            throw unsupported(declared == null ? "未知" : declared);
        }

        String source = sourceName == null ? "" : sourceName;
        return switch (format) {
            case "csv" -> parseCsv(content, packageVersion, source);
            case "json" -> parseJson(content, packageVersion, source);
            default -> throw unsupported(format);
        };
    }

    /** E_PKG_001：明确「不支持的格式」+ 支持格式清单 + 可执行转换提示。 */
    private static AppException unsupported(String format) {
        return new AppException(ErrorCodes.E_PKG_001,
                "不支持的格式（" + format + "）：当前支持格式 csv / json；"
                        + "可执行转换提示：用 Excel 另存为 CSV UTF-8 后再导入，或导出为标准 JSON 口径包后再导入")
                .with("format", format)
                .with("supported", "csv / json")
                .hinting(ErrorCodes.hint(ErrorCodes.E_PKG_001));
    }

    // ------------------------------------------------------------------ CSV

    private ParsedPackage parseCsv(String content, String packageVersion, String source) {
        if (content == null) {
            throw new AppException("E_DATA_001", "CSV 口径包内容为空");
        }
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rows.add(splitCsvLine(line));
            }
        } catch (IOException e) {
            throw new AppException("E_DATA_001", "读取 CSV 口径包失败：" + e.getMessage(), e);
        }
        if (rows.isEmpty()) {
            throw new AppException("E_DATA_001", "CSV 口径包缺少表头与数据行");
        }
        String[] header = rows.get(0);
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            String canonical = HEADER_ALIASES.get(normalizeHeader(header[i]));
            if (canonical != null) col.putIfAbsent(canonical, i);
        }

        List<PackageEntry> entries = new ArrayList<>();
        String inferredVersion = packageVersion;
        for (int r = 1; r < rows.size(); r++) {
            String[] cells = rows.get(r);
            if (inferredVersion == null && col.containsKey("package_version")) {
                String v = cell(cells, col.get("package_version"));
                if (!v.isBlank()) inferredVersion = v;
            }
            PackageEntry.Builder b = PackageEntry.builder()
                    .entryNo(cell(cells, col.get("entry_no")))
                    .indicatorCode(cell(cells, col.get("indicator_code")))
                    .indicatorName(cell(cells, col.get("indicator_name")))
                    .definition(cell(cells, col.get("definition")))
                    .numerator(cell(cells, col.get("numerator")))
                    .denominator(cell(cells, col.get("denominator")))
                    .effectiveFrom(cell(cells, col.get("effective_from")))
                    .effectiveTo(cell(cells, col.get("effective_to")));
            String depts = cell(cells, col.get("dept_codes"));
            if (!depts.isBlank()) b.deptCodes(splitDeptCodes(depts));
            entries.add(b.build());
        }
        String version = inferredVersion == null || inferredVersion.isBlank() ? "v1" : inferredVersion;
        return new ParsedPackage(version, "csv", source, entries);
    }

    private static String cell(String[] cells, Integer idx) {
        if (idx == null || idx < 0 || idx >= cells.length) return "";
        return cells[idx] == null ? "" : cells[idx].trim();
    }

    private static List<String> splitDeptCodes(String raw) {
        String[] parts = raw.split("[;|、,，]");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 简易 CSV 行切分：支持双引号包裹与字段内逗号、双写引号转义。 */
    private static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') inQuotes = true;
                else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
                else cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ JSON

    private ParsedPackage parseJson(String content, String packageVersion, String source) {
        if (content == null || content.isBlank()) {
            throw new AppException("E_DATA_001", "JSON 口径包内容为空");
        }
        JsonNode root = JsonSupport.parse(content); // 基于 Jackson ObjectMapper
        String version = packageVersion;
        JsonNode entriesNode;
        if (root.isArray()) {
            entriesNode = root;
        } else {
            if (version == null || version.isBlank()) {
                version = root.path("package_version").asText("");
            }
            JsonNode e = root.path("entries");
            if (!e.isArray()) e = root.path("indicators");
            if (!e.isArray()) e = root.path("items");
            if (!e.isArray()) {
                throw new AppException("E_DATA_001",
                        "JSON 口径包缺少条目数组（entries / indicators / items）");
            }
            entriesNode = e;
        }
        List<PackageEntry> entries = new ArrayList<>();
        for (JsonNode n : entriesNode) entries.add(PackageEntry.fromJson(n));
        String v = version == null || version.isBlank() ? "v1" : version;
        return new ParsedPackage(v, "json", source, entries);
    }
}
