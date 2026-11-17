package com.infection.metric.metriclib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 官方基准口径集：从 classpath 资源 /benchmarks/mrq_control_2021.json 加载 27 项指标。
 * 分母复用结构按 indicators 里 denominator_key 实际统计，而不是直接采信声明值，
 * 因此「同期住院手术患者病历总数」与「同期出院手术患者病历总数」天然是两个不同口径。
 */
public final class BenchmarkSet {

    private static final String RESOURCE = "/benchmarks/mrq_control_2021.json";
    private static final int OFFICIAL_COUNT = 27;

    private final ObjectNode root;
    private final List<JsonNode> indicators = new ArrayList<>();
    private final Map<String, JsonNode> byCode = new LinkedHashMap<>();
    private final Map<String, JsonNode> byIndicatorNo = new LinkedHashMap<>();
    private final Map<String, List<String>> groupCodes = new LinkedHashMap<>();

    private BenchmarkSet(ObjectNode root) {
        this.root = root;
        JsonNode arr = root.path("indicators");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                indicators.add(n);
                byCode.put(n.path("indicator_code").asText(), n);
                byIndicatorNo.put(n.path("indicator_no").asText(), n);
                String key = n.path("denominator_key").asText("");
                if (!key.isEmpty()) {
                    groupCodes.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(n.path("indicator_code").asText());
                }
            }
        }
    }

    /** 从 classpath 内置资源加载；资源缺失或不是对象时显式失败。 */
    public static BenchmarkSet load() {
        JsonNode n = JsonSupport.readResource(RESOURCE);
        if (n == null || !n.isObject()) {
            throw invalid("内置资源 " + RESOURCE + " 不是合法 JSON 对象");
        }
        return new BenchmarkSet((ObjectNode) n);
    }

    public int size() { return indicators.size(); }

    /** 全部指标编码（indicator_code），保持资源内顺序。 */
    public List<String> codes() { return List.copyOf(byCode.keySet()); }

    /** 全部官方指标编号（指标一 … 指标二十七）。 */
    public List<String> indicatorNos() { return List.copyOf(byIndicatorNo.keySet()); }

    /** 按指标编码取原始指标节点；不存在返回 null。 */
    public JsonNode byCode(String code) { return byCode.get(code); }

    /** 按官方指标编号取原始指标节点；不存在返回 null。 */
    public JsonNode byIndicatorNo(String no) { return byIndicatorNo.get(no); }

    /** 复用某分母的指标数（按指标实际 denominator_key 统计，不采信声明值）。 */
    public int denominatorGroup(String name) {
        List<String> codes = groupCodes.get(name);
        return codes == null ? 0 : codes.size();
    }

    /** 复用某分母的指标编码清单。 */
    public List<String> denominatorGroupCodes(String name) {
        List<String> codes = groupCodes.get(name);
        return codes == null ? List.of() : List.copyOf(codes);
    }

    /** 资源中 denominator_groups 声明的 expected_count，供对照校验。 */
    public Map<String, Integer> declaredGroupCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        JsonNode groups = root.path("denominator_groups");
        Iterator<Map.Entry<String, JsonNode>> it = groups.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            out.put(e.getKey(), e.getValue().path("expected_count").asInt(0));
        }
        return out;
    }

    /** 校验基准集：27 项齐全、编号唯一、confidence 全为 official、四个分母统计与声明一致。 */
    public void assertOfficial() {
        if (size() != OFFICIAL_COUNT) {
            throw invalid("指标项数应为 " + OFFICIAL_COUNT + "，实际 " + size());
        }
        if (byIndicatorNo.size() != OFFICIAL_COUNT || byIndicatorNo.containsKey("")) {
            throw invalid("27 项官方编号不齐全或存在空编号");
        }
        for (JsonNode n : indicators) {
            if (!"official".equals(n.path("confidence").asText(""))) {
                throw invalid("指标 " + n.path("indicator_code").asText() + " 的 confidence 不是 official");
            }
        }
        for (Map.Entry<String, Integer> e : declaredGroupCounts().entrySet()) {
            int actual = denominatorGroup(e.getKey());
            if (actual != e.getValue()) {
                throw invalid("分母「" + e.getKey() + "」归属指标数应为 "
                        + e.getValue() + "，实际统计 " + actual);
            }
        }
    }

    private static AppException invalid(String reason) {
        return ErrorCodes.error(ErrorCodes.E_DATA_001, "field", "官方基准集")
                .with("校验项", reason)
                .hinting("核对 src/main/resources/benchmarks/mrq_control_2021.json，禁止编造或改写官方条目");
    }
}
