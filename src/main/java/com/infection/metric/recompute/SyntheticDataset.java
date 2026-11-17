package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.AtomicFiles;
import com.infection.metric.common.DataRecord;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.expr.ConditionTree;
import com.infection.metric.metriclib.MetricDefinition;
import com.infection.metric.metriclib.MetricStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 规则化合成数据层（任务9）：读 classpath 资源，用 {@link BufferedReader} 解析 CSV，
 * 加载 4 季度 × 1200 条出院记录 + 180 条院感明细 + 39 条口径 + 156 项 oracle。
 *
 * <p>口径条件树是参数化的（{@code @quarter} 占位符）与简化版的分子范围，
 * 本类把每条口径「物化」为可复算的真实条件树：官方指标分子按对应质量字段计数，
 * 院感草稿指标分子取院感明细的 mdr_flag；分母恒取该季度出院病历总数。
 * 这套物化规则与生成器 {@code compute_oracle} 的独立规则化计数相呼应，
 * 复算数字从 CSV 明细重新求值得到，再与 oracle.json 的标准答案逐条比对 —— 两条独立路径。</p>
 *
 * <p>复算结果按 {@code data/<年度>/<季度>/<指标>.json} 原子落盘（仅聚合值 + 口径元数据），
 * 可按年分区列出并再次复算；运行摘要另行按 {@code data/runs/<年度>/<运行标识>/} 落盘。</p>
 */
public final class SyntheticDataset {

    public static final String DATA_SOURCE_VERSION = "synthetic-20261109";

    private static final String ENCOUNTERS_RES = "/samples/synthetic-encounters.csv";
    private static final String INFECTIONS_RES = "/samples/synthetic-infection-reports.csv";
    private static final String INDICATORS_RES = "/samples/synthetic-indicators.json";
    private static final String ORACLE_RES = "/samples/synthetic-oracle.json";
    private static final String MANIFEST_RES = "/samples/synthetic-manifest.json";

    private final List<String> quarters;
    private final List<DataRecord> encountersAll;
    private final List<DataRecord> infectionsAll;
    private final Map<String, List<DataRecord>> encountersByQuarter = new LinkedHashMap<>();
    private final Map<String, List<DataRecord>> infectionsByQuarter = new LinkedHashMap<>();
    private final List<IndicatorSpec> indicators;
    private final Map<String, ExpectedValue> oracle = new LinkedHashMap<>();
    private final JsonNode manifest;

    private SyntheticDataset(JsonNode manifest, List<String> quarters,
                            List<DataRecord> encounters, List<DataRecord> infections,
                            List<IndicatorSpec> indicators, Map<String, ExpectedValue> oracle) {
        this.manifest = manifest;
        this.quarters = List.copyOf(quarters);
        this.encountersAll = List.copyOf(encounters);
        this.infectionsAll = List.copyOf(infections);
        this.indicators = List.copyOf(indicators);
        this.oracle.putAll(oracle);
        for (String q : quarters) {
            encountersByQuarter.put(q, filterByQuarter(encountersAll, q));
            infectionsByQuarter.put(q, filterByQuarter(infectionsAll, q));
        }
    }

    /** 从 classpath 资源加载整套合成数据（测试用 @TempDir 落盘，不污染项目根 data/）。 */
    public static SyntheticDataset load() {
        JsonNode manifest = JsonSupport.readResource(MANIFEST_RES);
        List<String> quarters = JsonSupport.stringList(manifest.path("quarters"));
        if (quarters.isEmpty()) {
            throw new AppException(ErrorCodes.E_DATA_001, "合成数据 manifest 缺少 quarters");
        }
        List<DataRecord> encounters = readCsv(ENCOUNTERS_RES);
        List<DataRecord> infections = readCsv(INFECTIONS_RES);

        JsonNode indRoot = JsonSupport.readResource(INDICATORS_RES);
        List<IndicatorSpec> specs = new ArrayList<>();
        for (JsonNode n : indRoot.path("indicators")) {
            specs.add(IndicatorSpec.fromJson(n));
        }

        JsonNode orcRoot = JsonSupport.readResource(ORACLE_RES);
        Map<String, ExpectedValue> oracle = new LinkedHashMap<>();
        for (JsonNode n : orcRoot.path("oracle")) {
            String key = n.path("indicator_code").asText("") + "|"
                    + n.path("definition_version").asText("") + "|"
                    + n.path("quarter").asText("");
            oracle.put(key, new ExpectedValue(
                    n.path("expected_numerator").asInt(),
                    n.path("expected_denominator").asInt(),
                    n.path("oracle_rule").asText("")));
        }
        return new SyntheticDataset(manifest, quarters, encounters, infections, specs, oracle);
    }

    // ---------------------------------------------------------------- 数据访问

    public List<String> quarters() { return quarters; }
    public List<DataRecord> encountersAll() { return encountersAll; }
    public List<DataRecord> infectionsAll() { return infectionsAll; }
    public List<IndicatorSpec> indicators() { return indicators; }
    public JsonNode manifest() { return manifest; }

    public int oracleRowCount() { return oracle.size(); }

    public int officialCount() {
        return (int) indicators.stream().filter(s -> s.isOfficial()).count();
    }

    public int draftCount() {
        return (int) indicators.stream().filter(s -> !s.isOfficial()).count();
    }

    public List<DataRecord> encounters(String quarter) {
        return encountersByQuarter.getOrDefault(quarter, List.of());
    }

    public List<DataRecord> infections(String quarter) {
        return infectionsByQuarter.getOrDefault(quarter, List.of());
    }

    /** 取某项 oracle 期望值（标准答案）。 */
    public ExpectedValue oracle(String code, String version, String quarter) {
        return oracle.get(code + "|" + version + "|" + quarter);
    }

    public ExpectedValue oracle(String code, String quarter) {
        return oracle.get(code + "|2026.1|" + quarter);
    }

    // ---------------------------------------------------------------- 复算

    /** 仅对官方 27 项 × 各季度求值（不落盘），供规模性能验收 = 108 次求值。 */
    public List<RecomputeResult> recomputeOfficial() {
        return computeAll(true);
    }

    /**
     * 全量复算（39 指标 × 4 季度）：按年度分区原子落盘结果，写运行摘要，
     * 并以 oracle 为准逐项核对，差异写入摘要的失败步骤（可定位到具体季度与指标）。
     */
    public RunSummary recomputeAll(Path dataRoot, int year) {
        return recomputeAll(dataRoot, year, Map.of());
    }

    /** 带 oracle 覆盖的复算入口：便于注入一处不一致，验证差异可在运行摘要定位。 */
    public RunSummary recomputeAll(Path dataRoot, int year,
                                   Map<String, ExpectedValue> oracleOverride) {
        List<RecomputeResult> allResults = computeAll(false);

        RunSummary summary = new RunSummary(DATA_SOURCE_VERSION, quarters);
        long fetch = 0, compute = 0;
        for (RecomputeResult r : allResults) {
            fetch += r.getFetchMillis();
            compute += r.getComputeMillis();
            summary.addResult(r);
        }
        summary.setTimings(fetch, compute);

        // oracle 逐项对照：不一致 / 失败都落入摘要失败步骤，带具体季度与指标
        for (RecomputeResult r : allResults) {
            String shortKey = r.getIndicatorCode() + "|" + r.getQuarter();
            ExpectedValue expected = oracleOverride != null && oracleOverride.containsKey(shortKey)
                    ? oracleOverride.get(shortKey)
                    : oracle.get(r.getIndicatorCode() + "|" + r.getDefinitionVersion() + "|" + r.getQuarter());
            if (expected == null) {
                summary.addFailedStep(r.getIndicatorCode(), r.getQuarter(), "oracle对照",
                        "季度 " + r.getQuarter() + " 指标 " + r.getIndicatorCode()
                                + " 缺少 oracle 期望值，无法核对");
            } else if (r.isFailed() || !expected.matches(r)) {
                summary.addFailedStep(r.getIndicatorCode(), r.getQuarter(), "oracle对照",
                        "季度 " + r.getQuarter() + " 指标 " + r.getIndicatorCode()
                                + " 复算结果与 oracle 不一致：期望分子 " + expected.getNumerator()
                                + " / 分母 " + expected.getDenominator()
                                + "，实际 " + r.getNumerator() + " / " + r.getDenominator()
                                + (r.isFailed() ? "（" + r.getFailureReason() + "）" : ""));
            }
        }

        // 复算结果按年度分区落盘：data/<年度>/<季度>/<指标>.json（原子写，仅聚合值）
        for (String q : quarters) {
            Path qDir = dataRoot.resolve(String.valueOf(year)).resolve(q);
            AtomicFiles.ensureDir(qDir);
            summary.addLandedQuarter(q);
        }
        for (RecomputeResult r : allResults) {
            Path p = dataRoot.resolve(String.valueOf(year)).resolve(r.getQuarter())
                    .resolve(safeName(r.getIndicatorCode()) + ".json");
            JsonSupport.writeAtomic(p, r.toJson());
        }

        // 运行摘要落盘：data/runs/<年度>/<runId>/summary.json（含产物定位信息）
        summary.write(dataRoot, year);
        return summary;
    }

    /** 列出某年度已落盘的季度分区。 */
    public List<String> listLandedQuarters(Path dataRoot, int year) {
        Path yDir = dataRoot.resolve(String.valueOf(year));
        if (!Files.isDirectory(yDir)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(yDir)) {
            s.filter(p -> Files.isDirectory(p))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("\\d{4}Q[1-4]"))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            throw new AppException(ErrorCodes.E_STORE_001,
                    "列出年度分区失败：" + yDir + "：" + e.getMessage(), e);
        }
        return out;
    }

    /** 从年度分区读回全部复算结果，用于「再次复算 / 核对」。 */
    public List<RecomputeResult> reloadResults(Path dataRoot, int year) {
        List<RecomputeResult> out = new ArrayList<>();
        for (String q : listLandedQuarters(dataRoot, year)) {
            Path qDir = dataRoot.resolve(String.valueOf(year)).resolve(q);
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.list(qDir)) {
                s.filter(p -> p.toString().endsWith(".json")).sorted().forEach(files::add);
            } catch (IOException e) {
                throw new AppException(ErrorCodes.E_STORE_001,
                        "读取季度分区失败：" + qDir + "：" + e.getMessage(), e);
            }
            for (Path p : files) {
                out.add(RecomputeResult.fromJson(JsonSupport.read(p)));
            }
        }
        out.sort((a, b) -> {
            int c = a.getQuarter().compareTo(b.getQuarter());
            return c != 0 ? c : a.getIndicatorCode().compareTo(b.getIndicatorCode());
        });
        return out;
    }

    // ---------------------------------------------------------------- 内部

    private List<RecomputeResult> computeAll(boolean officialOnly) {
        List<RecomputeResult> out = new ArrayList<>();
        for (IndicatorSpec spec : indicators) {
            if (officialOnly && !spec.isOfficial()) continue;
            RecordSupplier supplier = q -> QuarterRecords.of(
                    spec.isHai() ? infections(q) : encounters(q),  // 分子记录集
                    encounters(q));                                 // 分母记录集（出院病历总数）
            RecomputeEngine engine = new RecomputeEngine(supplier, DATA_SOURCE_VERSION);
            MetricDefinition def = spec.toDefinition();
            for (String q : quarters) {
                out.add(engine.recompute(def, q));
            }
        }
        return out;
    }

    private static List<DataRecord> filterByQuarter(List<DataRecord> rows, String quarter) {
        List<DataRecord> out = new ArrayList<>();
        for (DataRecord r : rows) {
            if (quarter.equals(r.asText("quarter"))) out.add(r);
        }
        return List.copyOf(out);
    }

    private static List<DataRecord> readCsv(String resource) {
        InputStream in = SyntheticDataset.class.getResourceAsStream(resource);
        if (in == null) {
            throw new AppException("E_DATA_003", "内置合成数据资源缺失：" + resource);
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) {
                throw new AppException("E_DATA_003", "合成 CSV 为空：" + resource);
            }
            String[] cols = header.split(",", -1);
            for (int i = 0; i < cols.length; i++) cols[i] = cols[i].trim();
            List<DataRecord> rows = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] vals = line.split(",", -1);
                DataRecord.Builder b = DataRecord.builder();
                for (int i = 0; i < cols.length; i++) {
                    b.put(cols[i], i < vals.length ? vals[i] : "");
                }
                rows.add(b.build());
            }
            return rows;
        } catch (IOException e) {
            throw new AppException("E_DATA_003",
                    "读取合成 CSV 失败：" + resource + "：" + e.getMessage(), e);
        }
    }

    private static String safeName(String raw) {
        return raw == null ? "unknown" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ---------------------------------------------------------------- 数据载体

    /** 期望指标值（oracle.json 的一条标准答案）。 */
    public static final class ExpectedValue {
        private final int numerator;
        private final int denominator;
        private final String rule;

        public ExpectedValue(int numerator, int denominator, String rule) {
            this.numerator = numerator;
            this.denominator = denominator;
            this.rule = rule == null ? "" : rule;
        }

        public int getNumerator() { return numerator; }
        public int getDenominator() { return denominator; }
        public String getRule() { return rule; }

        /** 复算结果是否与期望值逐项相等。 */
        public boolean matches(RecomputeResult r) {
            return r != null && r.hasValidNumbers()
                    && r.getNumerator() == numerator
                    && r.getDenominator() == denominator;
        }
    }

    /** 合成口径条目：附带物化真实分子条件所需的信息。 */
    public static final class IndicatorSpec {
        private final String code;
        private final String version;
        private final String title;
        private final String denominatorKey;
        private final String confidence;
        private final String basis;
        private final ConditionTree numerator;
        private final ConditionTree denominator;
        private final List<String> deptCodes;
        private final List<String> periods;
        private final boolean hai;
        private final String numeratorField;

        private IndicatorSpec(String code, String version, String title, String denominatorKey,
                              String confidence, String basis, ConditionTree numerator,
                              ConditionTree denominator, List<String> deptCodes, List<String> periods) {
            this.code = code;
            this.version = version;
            this.title = title;
            this.denominatorKey = denominatorKey;
            this.confidence = confidence;
            this.basis = basis;
            this.numerator = numerator;
            this.denominator = denominator;
            this.deptCodes = deptCodes;
            this.periods = periods;
            this.hai = code.startsWith("HAI-");
            this.numeratorField = hai ? "mdr_flag" : resolveNumeratorField(code, title);
        }

        static IndicatorSpec fromJson(JsonNode n) {
            String code = n.path("indicator_code").asText("");
            String version = n.path("definition_version").asText("");
            ConditionTree num = n.has("numerator")
                    ? ConditionTree.fromJson(n.path("numerator")) : null;
            ConditionTree den = n.has("denominator")
                    ? ConditionTree.fromJson(n.path("denominator")) : null;
            return new IndicatorSpec(code, version,
                    n.path("title").asText(""),
                    n.path("denominator_key").asText(""),
                    n.path("confidence").asText("draft"),
                    n.path("basis").asText(""),
                    num, den,
                    JsonSupport.stringList(n.path("scope").path("deptCodes")),
                    JsonSupport.stringList(n.path("scope").path("periods")));
        }

        /** 官方口径（confidence=official）；HAI-* 一律为本院草稿。 */
        public boolean isOfficial() { return "official".equals(confidence); }
        public boolean isHai() { return hai; }

        public String getCode() { return code; }
        public String getVersion() { return version; }
        public String getTitle() { return title; }
        public String getDenominatorKey() { return denominatorKey; }
        public String getConfidence() { return confidence; }
        public String getBasis() { return basis; }
        public String getNumeratorField() { return numeratorField; }
        public List<String> getDeptCodes() { return deptCodes; }
        public List<String> getPeriods() { return periods; }

        /**
         * 物化为可复算口径：把简化条件树补全为真实分子范围。
         * 官方指标分子 = 该季度 + 对应质量字段命中；院感草稿分子 = 该季度 + mdr_flag。
         */
        public MetricDefinition toDefinition() {
            ConditionTree num;
            if (hai) {
                num = numerator; // 已含 quarter + mdr_flag
            } else {
                num = ConditionTree.and(
                        ConditionTree.leaf("quarter", "eq", "@quarter"),
                        ConditionTree.leaf(numeratorField, "eq", "true"));
            }
            return MetricDefinition.builder(code, version)
                    .title(title)
                    .numerator(num)
                    .denominator(denominator)
                    .deptCodes(deptCodes)
                    .periods(periods)
                    .status(MetricStatus.PUBLISHED)   // 已发布口径才能复算
                    .build();
        }

        /** 官方指标 → 分子质量字段的独立规则化映射（与生成器 compute_oracle 同规则）。 */
        private static String resolveNumeratorField(String code, String title) {
            switch (code) {
                case "MER-TL-01": return "admission_record_ontime";
                case "MER-TL-03": return "discharge_record_ontime";
                case "MER-TL-04": return "first_page_ontime";
                case "MER-TQ-03": return "primary_diag_correct";
                case "MER-TQ-09": return "grade_a_record";
                case "MER-TQ-01": return "archived_2d";
                default: break;
            }
            if (title != null && title.contains("手术")) return "had_surgery";
            return "grade_a_record";
        }
    }
}
