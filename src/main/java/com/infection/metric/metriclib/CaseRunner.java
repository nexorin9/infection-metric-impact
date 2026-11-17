package com.infection.metric.metriclib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 案例跑批器：以官方基准集为实际值来源批量执行案例，输出通过 / 失败计数与失败明细。
 * 失败明细逐条指出 case_id、字段、期望值与实际值差在哪，不用一句「失败」糊过去。
 * official 案例的指标编号必须能在 BenchmarkSet 中对齐；draft 案例只做可信度标注校验。
 */
public final class CaseRunner {

    private CaseRunner() {}

    public static Result run(CaseSet set) {
        BenchmarkSet bs = BenchmarkSet.load();
        Result result = new Result();
        for (CaseSet.Case c : set.allCases()) {
            if ("official".equals(c.getConfidence())) {
                result.record(runOfficial(c, bs));
            } else {
                result.record(runDraft(c));
            }
        }
        return result;
    }

    private static List<Result.Failure> runOfficial(CaseSet.Case c, BenchmarkSet bs) {
        List<Result.Failure> failures = new ArrayList<>();
        JsonNode input = c.getInput();
        JsonNode expect = c.getExpect();

        if (input.hasNonNull("indicator_code")) {
            String code = input.path("indicator_code").asText();
            JsonNode indicator = bs.byCode(code);
            if (indicator == null) {
                failures.add(new Result.Failure(c.getCaseId(), "indicator_code", code,
                        "官方基准集中无对应项"));
            } else {
                Iterator<String> fields = expect.fieldNames();
                while (fields.hasNext()) {
                    String f = fields.next();
                    Result.Failure mismatch = mismatch(c.getCaseId(), f,
                            expect.get(f), indicator.get(f));
                    if (mismatch != null) failures.add(mismatch);
                }
            }
        } else if (input.hasNonNull("denominator_text") && expect.has("indicator_count")) {
            int actual = bs.denominatorGroup(input.path("denominator_text").asText());
            Result.Failure mismatch = mismatch(c.getCaseId(), "indicator_count",
                    expect.get("indicator_count"), IntNode.valueOf(actual));
            if (mismatch != null) failures.add(mismatch);
        } else if (input.has("compare") && expect.has("counts")) {
            JsonNode compare = input.path("compare");
            ArrayNode actual = JsonSupport.array();
            if (compare.isArray()) {
                for (JsonNode name : compare) {
                    actual.add(bs.denominatorGroup(name.asText()));
                }
            }
            Result.Failure mismatch = mismatch(c.getCaseId(), "counts", expect.get("counts"), actual);
            if (mismatch != null) failures.add(mismatch);
            if (expect.has("distinct")) {
                boolean distinct = compare.isArray() && compare.size() >= 2
                        && !compare.get(0).asText().equals(compare.get(1).asText());
                Result.Failure dm = mismatch(c.getCaseId(), "distinct",
                        expect.get("distinct"), BooleanNode.valueOf(distinct));
                if (dm != null) failures.add(dm);
            }
        }
        return failures;
    }

    private static List<Result.Failure> runDraft(CaseSet.Case c) {
        List<Result.Failure> failures = new ArrayList<>();
        if (!"draft".equals(c.getConfidence())) {
            failures.add(new Result.Failure(c.getCaseId(), "confidence", "draft", c.getConfidence()));
        }
        return failures;
    }

    private static Result.Failure mismatch(String caseId, String field,
                                           JsonNode expected, JsonNode actual) {
        JsonNode exp = expected == null ? NullNode.getInstance() : expected;
        JsonNode act = actual == null ? NullNode.getInstance() : actual;
        if (exp.equals(act)) return null;
        return new Result.Failure(caseId, field, exp.toString(), act.toString());
    }

    /** 跑批结果与失败明细。 */
    public static final class Result {
        private int passed;
        private int failed;
        private final List<Failure> failures = new ArrayList<>();

        void record(List<Failure> caseFailures) {
            if (caseFailures.isEmpty()) {
                passed++;
            } else {
                failed++;
                failures.addAll(caseFailures);
            }
        }

        public int passedCount() { return passed; }
        public int failedCount() { return failed; }
        public List<Failure> failures() { return List.copyOf(failures); }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("案例跑批结果：通过 ").append(passed)
                    .append(" 个，失败 ").append(failed).append(" 个。");
            for (Failure f : failures) {
                sb.append("\n  失败案例 ").append(f.getCaseId())
                        .append("，字段 ").append(f.getField())
                        .append("：期望 ").append(f.getExpected())
                        .append("，实际 ").append(f.getActual());
            }
            return sb.toString();
        }

        /** 单条失败：明确指出期望值与实际值。 */
        public static final class Failure {
            private final String caseId;
            private final String field;
            private final String expected;
            private final String actual;

            public Failure(String caseId, String field, String expected, String actual) {
                this.caseId = caseId;
                this.field = field;
                this.expected = expected;
                this.actual = actual;
            }

            public String getCaseId() { return caseId; }
            public String getField() { return field; }
            public String getExpected() { return expected; }
            public String getActual() { return actual; }
        }
    }
}
