package com.infection.metric.metriclib;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 案例集：从指定目录读取 official_benchmark_cases.json（官方口径案例）与
 * hai_draft_cases.json（院感侧草稿案例）。案例在项目根 cases/ 而非 classpath，
 * 因此统一走 fromDirectory(Path)。加载时强制校验案例结构与可信度标注，
 * draft 不得冒充 official，official 案例集也不得混入草稿。
 */
public final class CaseSet {

    public static final String OFFICIAL_FILE = "official_benchmark_cases.json";
    public static final String DRAFT_FILE = "hai_draft_cases.json";

    private final List<Case> official;
    private final List<Case> draft;

    private CaseSet(List<Case> official, List<Case> draft) {
        this.official = List.copyOf(official);
        this.draft = List.copyOf(draft);
    }

    public static CaseSet fromDirectory(Path dir) {
        return new CaseSet(readCases(dir.resolve(OFFICIAL_FILE), "official"),
                readCases(dir.resolve(DRAFT_FILE), "draft"));
    }

    private static List<Case> readCases(Path file, String expectedConfidence) {
        if (!Files.exists(file)) {
            throw ErrorCodes.error(ErrorCodes.E_DATA_001, "field", file.getFileName().toString())
                    .with("path", file.toString())
                    .hinting("案例集目录需同时包含 " + OFFICIAL_FILE + " 与 " + DRAFT_FILE);
        }
        JsonNode root = JsonSupport.read(file);
        JsonNode arr = root.path("cases");
        if (!arr.isArray()) {
            throw ErrorCodes.error(ErrorCodes.E_DATA_001, "field", "cases")
                    .with("path", file.toString());
        }
        List<Case> out = new ArrayList<>();
        for (JsonNode n : arr) {
            Case c = Case.fromJson(n);
            c.validateStructure();
            if (!expectedConfidence.equals(c.getConfidence())) {
                throw ErrorCodes.error(ErrorCodes.E_DATA_001, "field", "confidence")
                        .with("case_id", c.getCaseId())
                        .with("期望", expectedConfidence)
                        .with("实际", c.getConfidence())
                        .hinting("official".equals(expectedConfidence)
                                ? "官方基准案例集不得混入非 official 标注"
                                : "院感草稿案例必须标 draft，不得冒充 official");
            }
            out.add(c);
        }
        return out;
    }

    public List<Case> officialCases() { return official; }
    public List<Case> draftCases() { return draft; }

    public List<Case> allCases() {
        List<Case> out = new ArrayList<>(official);
        out.addAll(draft);
        return List.copyOf(out);
    }

    public int size() { return official.size() + draft.size(); }

    /** 单个案例：case_id / input / expect / basis / confidence。 */
    public static final class Case {
        private final String caseId;
        private final JsonNode input;
        private final JsonNode expect;
        private final String basis;
        private final String confidence;

        private Case(String caseId, JsonNode input, JsonNode expect, String basis, String confidence) {
            this.caseId = caseId;
            this.input = input;
            this.expect = expect;
            this.basis = basis;
            this.confidence = confidence;
        }

        public String getCaseId() { return caseId; }
        public JsonNode getInput() { return input; }
        public JsonNode getExpect() { return expect; }
        public String getBasis() { return basis; }
        public String getConfidence() { return confidence; }

        public static Case fromJson(JsonNode n) {
            return new Case(
                    n.path("case_id").asText(""),
                    n.has("input") ? n.get("input") : null,
                    n.has("expect") ? n.get("expect") : null,
                    n.path("basis").asText(""),
                    n.path("confidence").asText(""));
        }

        /** 案例格式齐全性校验；缺任一必需字段显式失败，不静默跳过。 */
        public void validateStructure() {
            if (caseId == null || caseId.isBlank()) {
                throw bad("case_id");
            }
            if (input == null || !input.isObject()) {
                throw bad("input");
            }
            if (expect == null || !expect.isObject()) {
                throw bad("expect");
            }
            if (basis == null || basis.isBlank()) {
                throw bad("basis");
            }
            if (confidence == null || confidence.isBlank()) {
                throw bad("confidence");
            }
        }

        private AppException bad(String field) {
            return ErrorCodes.error(ErrorCodes.E_DATA_001, "field", field)
                    .with("case_id", caseId == null ? "" : caseId)
                    .hinting("案例必须含 case_id / input / expect / basis / confidence");
        }
    }
}
