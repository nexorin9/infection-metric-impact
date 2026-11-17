package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.metriclib.MetricStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型任务契约（任务43）：口径散文 → 结构化条件树，task = extract_definition。
 *
 * <p>请求 payload 含口径原文、指标编码、候选字段清单与数据源名；响应契约固定为
 * {@code {numerator[], denominator[], exclusions[], scope{}, citations[]}}，
 * citations 每条必须含原文片段引用。字段缺失、类型不符、引用为空一律判 E_LLM_004，
 * 不猜测修复、不重试。</p>
 *
 * <p>模型输出永远是草稿：抽取结果先入草稿（来源「模型」、状态 DRAFT），人工可改写；
 * 改写后来源标注「人工」并留下改写记录，状态不因模型或改写自动推进。</p>
 */
public final class ExtractContract implements TaskContract {

    public static final String TASK = "extract_definition";
    public static final String SCHEMA_VERSION = "1.0";

    @Override
    public String task() { return TASK; }

    @Override
    public String schemaVersion() { return SCHEMA_VERSION; }

    /** 构造合规请求 payload（口径原文 / 指标编码 / 候选字段清单 / 数据源名）。 */
    public static ObjectNode buildPayload(String definitionText, String indicatorCode,
                                          List<String> candidateFields, String dataSource) {
        ObjectNode payload = JsonSupport.object();
        payload.put("definition_text", definitionText);
        payload.put("indicator_code", indicatorCode);
        ArrayNode fields = payload.putArray("candidate_fields");
        if (candidateFields != null) {
            for (String f : candidateFields) fields.add(f);
        }
        payload.put("data_source", dataSource);
        return payload;
    }

    /** 构造一份合规的抽取响应（供契约测试与演示使用）。 */
    public static ObjectNode sampleResponse() {
        ObjectNode o = JsonSupport.object();
        o.putArray("numerator").addObject().put("field", "infection_site").put("cmp", "eq").put("value", "肺部");
        o.putArray("denominator").addObject().put("field", "discharge_date").put("cmp", "between").put("value", "2026Q1");
        o.putArray("exclusions");
        ObjectNode scope = o.putObject("scope");
        scope.putArray("deptCodes").add("0301");
        scope.putArray("periods").add("2026Q1");
        o.putArray("citations").addObject()
                .put("quote", "出院日期在 2026 年第一季度且感染部位为肺部")
                .put("field", "infection_site");
        return o;
    }

    @Override
    public void validateResponse(JsonNode response, JsonNode request) {
        ContractSchemas.requireObject(response, "extract_definition 响应");
        ContractSchemas.requireArrayField(response, "numerator");
        ContractSchemas.requireArrayField(response, "denominator");
        ContractSchemas.requireArrayField(response, "exclusions");
        ContractSchemas.requireObjectField(response, "scope");
        JsonNode citations = ContractSchemas.requireArrayField(response, "citations");
        if (citations.isEmpty()) {
            throw ContractSchemas.fail("citations 引用为空，抽取结果不可信");
        }
        int i = 0;
        for (JsonNode c : citations) {
            if (!hasQuote(c)) {
                throw ContractSchemas.fail("citations[" + i + "] 缺少原文片段引用（quote/text/excerpt）");
            }
            i++;
        }
    }

    /** 引用可以是纯文本片段，也可以是带 quote / text / excerpt 字段的对象。 */
    private static boolean hasQuote(JsonNode citation) {
        if (citation == null) return false;
        if (citation.isTextual()) return !citation.asText().isBlank();
        if (citation.isObject()) {
            for (String key : new String[]{"quote", "text", "excerpt", "source_text", "snippet", "原文"}) {
                JsonNode n = citation.get(key);
                if (n != null && n.isTextual() && !n.asText().isBlank()) return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 草稿落库与人工改写

    /** 一条改写记录：改前 / 改后 / 改写人 / 时间。 */
    public static final class RewriteRecord {
        private final String editor;
        private final JsonNode before;
        private final JsonNode after;
        private final long atMillis;

        RewriteRecord(String editor, JsonNode before, JsonNode after) {
            this.editor = editor;
            this.before = before.deepCopy();
            this.after = after.deepCopy();
            this.atMillis = System.currentTimeMillis();
        }

        public String getEditor() { return editor; }
        public JsonNode getBefore() { return before; }
        public JsonNode getAfter() { return after; }
        public long getAtMillis() { return atMillis; }
    }

    /** 抽取草稿：初始来源「模型」，人工改写后来源「人工」；状态恒为 DRAFT。 */
    public static final class Draft {
        private final String id;
        private final String indicatorCode;
        private final MetricStatus status = MetricStatus.DRAFT;
        private final List<RewriteRecord> rewrites = new ArrayList<>();
        private JsonNode content;
        private String source;

        Draft(String id, String indicatorCode, JsonNode content) {
            this.id = id;
            this.indicatorCode = indicatorCode;
            this.content = content == null ? JsonSupport.object() : content.deepCopy();
            this.source = "模型";
        }

        public String getId() { return id; }
        public String getIndicatorCode() { return indicatorCode; }
        public MetricStatus getStatus() { return status; }
        public String getSource() { return source; }
        public JsonNode getContent() { return content; }
        public List<RewriteRecord> getRewrites() { return List.copyOf(rewrites); }

        void applyRewrite(JsonNode newContent, String editor) {
            rewrites.add(new RewriteRecord(editor, this.content, newContent));
            this.content = newContent.deepCopy();
            this.source = "人工";
        }
    }

    /** 草稿库：抽取结果先入草稿；人工改写标注来源并留改写记录。 */
    public static final class DraftStore {
        private final Map<String, Draft> drafts = new LinkedHashMap<>();
        private int seq = 0;

        /** 抽取结果入草稿（来源「模型」、状态 DRAFT）。 */
        public Draft save(String indicatorCode, JsonNode extractionResult) {
            String id = "draft-" + (++seq);
            Draft draft = new Draft(id, indicatorCode, extractionResult);
            drafts.put(id, draft);
            return draft;
        }

        /** 人工改写：来源改标「人工」，追加改写记录；状态不变（仍 DRAFT）。 */
        public Draft rewrite(String draftId, JsonNode newContent, String editor) {
            Draft draft = drafts.get(draftId);
            if (draft == null) {
                throw ErrorCodes.error(ErrorCodes.E_STORE_001, "id", draftId);
            }
            if (editor == null || editor.isBlank()) {
                throw new AppException("E_AUDIT_001", "改写人不可为空");
            }
            draft.applyRewrite(newContent, editor);
            return draft;
        }

        public Draft get(String draftId) { return drafts.get(draftId); }
        public List<Draft> all() { return List.copyOf(drafts.values()); }
    }
}
