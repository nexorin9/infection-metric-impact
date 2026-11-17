package com.infection.metric.expr;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 口径归一化（canonical form）：AND/OR 子节点排序（交换律）、集合去重并排序、
 * in/not_in 元素顺序无关、between 上下界定序。指纹 = canonical JSON 的 sha256。
 * 「同口径 = 同指纹」全链路一致，用于依赖边匹配与复算缓存键。
 */
public final class Canonicalizer {

    private Canonicalizer() {}

    public static String canonicalJson(ConditionTree tree) {
        return canonicalNode(tree).toString();
    }

    public static ObjectNode canonicalNode(ConditionTree tree) {
        ObjectNode o = JsonSupport.object();
        if (tree.isLeaf()) {
            o.put("field", tree.getField().trim());
            o.put("cmp", tree.getCmp());
            o.set("value", canonicalValue(tree.getCmp(), tree.getValue()));
            return o;
        }
        o.put("op", tree.getOp());
        List<String> kidJsons = new ArrayList<>();
        for (ConditionTree c : tree.getChildren()) kidJsons.add(canonicalNode(c).toString());
        kidJsons.sort(Comparator.naturalOrder());
        ArrayNode arr = o.putArray("children");
        for (String s : kidJsons) arr.add(JsonSupport.parse(s));
        return o;
    }

    /** 集合去重并排序；between 上下界定序；其余保持原值。 */
    static com.fasterxml.jackson.databind.JsonNode canonicalValue(String cmp, Object value) {
        if (value instanceof List<?> list) {
            List<String> normalized = new ArrayList<>();
            for (Object e : list) normalized.add(stringify(e));
            if ("between".equals(cmp) && normalized.size() == 2) {
                normalized.sort(Canonicalizer::compareValues);
            } else if ("in".equals(cmp) || "not_in".equals(cmp)) {
                normalized = new ArrayList<>(new LinkedHashSet<>(normalized));
                normalized.sort(Comparator.naturalOrder());
            } else {
                normalized.sort(Comparator.naturalOrder());
            }
            ArrayNode arr = JsonSupport.array();
            for (String s : normalized) arr.add(s);
            return arr;
        }
        return ConditionTree.javaToJson(value);
    }

    private static String stringify(Object e) {
        if (e == null) return "null";
        return String.valueOf(e);
    }

    private static int compareValues(String a, String b) {
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    public static String fingerprint(ConditionTree tree) {
        return Hashes.sha256(canonicalJson(tree));
    }

    public static boolean equivalent(ConditionTree a, ConditionTree b) {
        if (a == null || b == null) return a == b;
        return fingerprint(a).equals(fingerprint(b));
    }

    /** 归一化后的等价树（保留结构，便于差异引擎逐条比较）。 */
    public static ConditionTree normalize(ConditionTree tree) {
        return ConditionTree.fromJson(canonicalNode(tree));
    }
}
