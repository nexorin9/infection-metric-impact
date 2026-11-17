package com.infection.metric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型任务契约（任务44）：同指标多措辞归并，task = merge_aliases。
 *
 * <p>请求 payload 含指标名称候选列表与各自出处；响应契约固定为
 * {@code [{left_id, right_id, confidence, reason}]}，confidence ∈ [0,1]、reason 非空、
 * left_id / right_id 必须引用真实候选 id。越界置信度、空 reason、引用不存在的 id
 * 一律判 E_LLM_004。</p>
 *
 * <p>候选先入「待确认清单」，主任逐条采纳或驳回；只有采纳后才影响对齐结果。</p>
 */
public final class AliasContract implements TaskContract {

    public static final String TASK = "merge_aliases";
    public static final String SCHEMA_VERSION = "1.0";

    @Override
    public String task() { return TASK; }

    @Override
    public String schemaVersion() { return SCHEMA_VERSION; }

    /** 构造合规请求 payload：候选列表每项含 id / name / source（出处）。 */
    public static ObjectNode buildPayload(String indicatorCode, List<String> candidateIds) {
        ObjectNode payload = JsonSupport.object();
        payload.put("indicator_code", indicatorCode);
        ArrayNode candidates = payload.putArray("candidates");
        if (candidateIds != null) {
            for (String id : candidateIds) {
                ObjectNode c = candidates.addObject();
                c.put("id", id);
                c.put("name", "候选名称-" + id);
                c.put("source", "上级口径包 2026.1 条目 " + id);
            }
        }
        return payload;
    }

    /** 构造一份合规的归并响应。 */
    public static ArrayNode sampleResponse(String leftId, String rightId) {
        ArrayNode arr = JsonSupport.array();
        ObjectNode o = arr.addObject();
        o.put("left_id", leftId);
        o.put("right_id", rightId);
        o.put("confidence", 0.86);
        o.put("reason", "两处名称仅措辞不同，指向同一指标编码");
        return arr;
    }

    @Override
    public void validateResponse(JsonNode response, JsonNode request) {
        ContractSchemas.requireContainer(response, "merge_aliases 响应");
        if (!response.isArray()) {
            throw ContractSchemas.fail("merge_aliases 响应应为数组");
        }
        Set<String> validIds = candidateIds(request);
        int i = 0;
        for (JsonNode item : response) {
            String where = "候选对[" + i + "]";
            ContractSchemas.requireObject(item, where);
            String left = ContractSchemas.requireNonBlankText(item, "left_id");
            String right = ContractSchemas.requireNonBlankText(item, "right_id");

            JsonNode confNode = item.get("confidence");
            if (confNode == null || !confNode.isNumber()) {
                throw ContractSchemas.fail(where + " confidence 缺失或类型不符（应为数值）");
            }
            double confidence = confNode.asDouble();
            if (confidence < 0.0 || confidence > 1.0) {
                throw ContractSchemas.fail(where + " confidence 越界：" + confidence + "（合法区间 [0,1]）");
            }
            ContractSchemas.requireNonBlankText(item, "reason");

            if (!validIds.isEmpty()) {
                if (!validIds.contains(left)) {
                    throw ContractSchemas.fail(where + " left_id 引用不存在的候选：" + left);
                }
                if (!validIds.contains(right)) {
                    throw ContractSchemas.fail(where + " right_id 引用不存在的候选：" + right);
                }
            }
            i++;
        }
    }

    private static Set<String> candidateIds(JsonNode request) {
        Set<String> ids = new LinkedHashSet<>();
        if (request != null && request.path("candidates").isArray()) {
            for (JsonNode c : request.path("candidates")) {
                String id = c.path("id").asText("");
                if (!id.isBlank()) ids.add(id);
            }
        }
        return ids;
    }

    // ---------------------------------------------------------------- 待确认清单与采纳流程

    /** 候选状态。 */
    public enum State { PENDING, ACCEPTED, REJECTED }

    /** 一条归并候选（待确认 / 已采纳 / 已驳回）。 */
    public static final class AliasCandidate {
        private final String id;
        private final String leftId;
        private final String rightId;
        private final double confidence;
        private final String reason;
        private State state = State.PENDING;
        private String reviewer;
        private long reviewedAtMillis;

        AliasCandidate(String id, JsonNode node) {
            this.id = id;
            this.leftId = node.path("left_id").asText("");
            this.rightId = node.path("right_id").asText("");
            this.confidence = node.path("confidence").isNumber()
                    ? node.path("confidence").asDouble() : 0.5;
            this.reason = node.path("reason").asText("");
        }

        public String getId() { return id; }
        public String getLeftId() { return leftId; }
        public String getRightId() { return rightId; }
        public double getConfidence() { return confidence; }
        public String getReason() { return reason; }
        public State getState() { return state; }
        public String getReviewer() { return reviewer; }
        public long getReviewedAtMillis() { return reviewedAtMillis; }

        void review(State target, String reviewer) {
            this.state = target;
            this.reviewer = reviewer;
            this.reviewedAtMillis = System.currentTimeMillis();
        }
    }

    /** 待确认清单：逐条采纳 / 驳回，采纳后才影响对齐结果。 */
    public static final class ReviewQueue {
        private final Map<String, AliasCandidate> queue = new LinkedHashMap<>();
        private int seq = 0;

        /** 把（已通过校验的）模型响应用作待确认候选清单。 */
        public List<AliasCandidate> addFromResponse(JsonNode response) {
            if (response == null || !response.isArray()) {
                throw ContractSchemas.fail("待确认清单需要数组响应");
            }
            List<AliasCandidate> added = new ArrayList<>();
            for (JsonNode item : response) {
                String id = "cand-" + (++seq);
                AliasCandidate c = new AliasCandidate(id, item);
                queue.put(id, c);
                added.add(c);
            }
            return added;
        }

        public AliasCandidate accept(String candidateId, String reviewer) {
            return review(candidateId, State.ACCEPTED, reviewer);
        }

        public AliasCandidate reject(String candidateId, String reviewer) {
            return review(candidateId, State.REJECTED, reviewer);
        }

        private AliasCandidate review(String candidateId, State target, String reviewer) {
            AliasCandidate c = queue.get(candidateId);
            if (c == null) {
                throw ErrorCodes.error(ErrorCodes.E_STORE_001, "id", candidateId);
            }
            if (reviewer == null || reviewer.isBlank()) {
                throw new AppException("E_AUDIT_001", "确认人不可为空");
            }
            c.review(target, reviewer);
            return c;
        }

        /** 影响对齐结果的仅有「已采纳」的候选对。 */
        public List<AliasCandidate> alignmentPairs() {
            List<AliasCandidate> out = new ArrayList<>();
            for (AliasCandidate c : queue.values()) {
                if (c.getState() == State.ACCEPTED) out.add(c);
            }
            return List.copyOf(out);
        }

        public List<AliasCandidate> pending() {
            List<AliasCandidate> out = new ArrayList<>();
            for (AliasCandidate c : queue.values()) {
                if (c.getState() == State.PENDING) out.add(c);
            }
            return List.copyOf(out);
        }

        public AliasCandidate get(String candidateId) { return queue.get(candidateId); }
        public List<AliasCandidate> all() { return List.copyOf(queue.values()); }
    }
}
