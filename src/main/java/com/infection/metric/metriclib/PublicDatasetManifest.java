package com.infection.metric.metriclib;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公开数据集清单（manifest）：联网下载结果落在 data/public/manifest.json；
 * 该文件不存在或断网时，退回 classpath 内置最小样例 /samples/public-sample.csv 并显式标注
 * provenance = bundled-minimal、status = degraded，绝不假装下载成功。
 */
public final class PublicDatasetManifest {

    private static final String RESOURCE = "/samples/public-sample.csv";
    private static final int MIN_ROWS = 1000;
    private static final List<String> REQUIRED_FIELDS =
            List.of("就诊标识", "出院日期", "科室代码", "主要诊断代码");

    private final String provenance;
    private final String status;
    private final String sha256;
    private final int rows;
    private final List<String> columns;
    private final Map<String, String> fieldMapping;

    private PublicDatasetManifest(String provenance, String status, String sha256,
                                  int rows, List<String> columns, Map<String, String> fieldMapping) {
        this.provenance = provenance == null ? "" : provenance;
        this.status = status == null ? "" : status;
        this.sha256 = sha256 == null ? "" : sha256;
        this.rows = rows;
        this.columns = List.copyOf(columns);
        this.fieldMapping = new LinkedHashMap<>(fieldMapping);
    }

    /** 优先读 data/public/manifest.json；不存在则构造内置降级标注。 */
    public static PublicDatasetManifest load() {
        Path p = Path.of("data", "public", "manifest.json");
        return Files.exists(p) ? load(p) : fromBundledSample();
    }

    /** 读取指定 manifest 文件（供测试自建临时目录使用）。 */
    public static PublicDatasetManifest load(Path manifestPath) {
        if (!Files.exists(manifestPath)) {
            throw ErrorCodes.error(ErrorCodes.E_DATA_001, "field", "manifest.json")
                    .with("path", manifestPath.toString())
                    .hinting("清单文件不存在；断网时请改用 fromBundledSample() 内置降级标注");
        }
        JsonNode n = JsonSupport.read(manifestPath);
        if (n == null || !n.isObject()) {
            throw ErrorCodes.error(ErrorCodes.E_DATA_001, "field", "manifest.json")
                    .with("path", manifestPath.toString());
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        JsonNode fm = n.path("field_mapping");
        Iterator<Map.Entry<String, JsonNode>> it = fm.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            mapping.put(e.getKey(), e.getValue().asText(""));
        }
        return new PublicDatasetManifest(
                n.path("provenance").asText(""),
                n.path("status").asText(""),
                n.path("sha256").asText(""),
                n.path("rows").asInt(0),
                JsonSupport.stringList(n.path("columns")),
                mapping);
    }

    /** 从内置样例 CSV 现场构造降级标注：行数、列名、sha256 都由样例本身算出。 */
    public static PublicDatasetManifest fromBundledSample() {
        String csv = readBundledCsv();
        List<String> lines = new ArrayList<>();
        for (String line : csv.split("\\R")) {
            if (!line.isBlank()) lines.add(line);
        }
        int dataRows = Math.max(0, lines.size() - 1);
        List<String> columns = lines.isEmpty() ? List.of()
                : Arrays.stream(lines.get(0).split(",")).map(String::trim).toList();
        return new PublicDatasetManifest("bundled-minimal", "degraded",
                Hashes.sha256(csv), dataRows, columns, builtinMapping());
    }

    public String provenance() { return provenance; }

    /** 数据行数（不含表头）。 */
    public int rows() { return rows; }

    public List<String> columns() { return columns; }

    public String sha256() { return sha256; }

    /** 外部列名 → 本产品出院记录业务字段。 */
    public Map<String, String> fieldMapping() { return Map.copyOf(fieldMapping); }

    /** 是否为断网降级（内置最小样例），而非真实下载成功。 */
    public boolean isDegraded() {
        return "bundled-minimal".equals(provenance) || "degraded".equals(status);
    }

    public String status() { return status; }

    /** 校验行数下限与字段映射；不达标显式失败，不静默按可用处理。 */
    public void validate() {
        if (rows < MIN_ROWS) {
            throw ErrorCodes.error(ErrorCodes.E_DATA_001, "field", "公开数据集行数")
                    .with("下限", String.valueOf(MIN_ROWS))
                    .with("实际", String.valueOf(rows))
                    .hinting("样例需达到就诊/出院记录量级；断网才允许降级到内置最小样例");
        }
        for (String required : REQUIRED_FIELDS) {
            if (!fieldMapping.containsValue(required)) {
                throw ErrorCodes.error(ErrorCodes.E_DATA_001, "field", required)
                        .hinting("字段映射必须包含 " + required + " 等出院记录字段");
            }
        }
    }

    private static Map<String, String> builtinMapping() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("encounter_id", "就诊标识");
        m.put("discharge_date", "出院日期");
        m.put("dept_code", "科室代码");
        m.put("primary_diag_code", "主要诊断代码");
        m.put("procedure_code", "手术操作代码");
        m.put("wound_class", "切口等级");
        return m;
    }

    private static String readBundledCsv() {
        try (InputStream in = PublicDatasetManifest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw ErrorCodes.error(ErrorCodes.E_DATA_001, "field", RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AppException("E_DATA_003", "读取内置公开样例失败：" + RESOURCE, e);
        }
    }
}
