package com.infection.metric.expr;

import com.infection.metric.common.ErrorCodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 口径表达式 ↔ 中文说明句双向渲染（任务30）。
 *
 * <p>条件树 → 中文说明句：每个比较符配一句业务说法
 * （between→「在 X 至 Y 之间」、in→「属于 …」、not_in→「不属于 …」、
 * eq→「等于」、contains→「包含」等），AND/OR 用「且」「或」连接、括号嵌套可读化；
 * 内部字段名经映射表译成业务中文名，不堆砌原始字段名黑话。</p>
 *
 * <p>受限模板反向解析：说明句 → 条件树，仅支持上述固定句式；遇到无法识别的句子
 * 抛 E_RENDER_001，不猜。render 与 parse 互逆，可验证「树 → 句 → 树」等价。</p>
 */
public final class HumanReadable {

    /** 内部字段名 → 业务中文名；业务字段名（如科室代码）以中文呈现，不堆砌内部名。 */
    private static final Map<String, String> FIELD_CN = new LinkedHashMap<>();
    /** 业务中文名 → 内部字段名，按中文名长度降序，便于前缀匹配。 */
    private static final List<Map.Entry<String, String>> CN_SORTED = new ArrayList<>();

    static {
        FIELD_CN.put("discharge_date", "出院日期");
        FIELD_CN.put("dept_code", "科室代码");
        FIELD_CN.put("diagnosis_code", "主要诊断代码");
        FIELD_CN.put("operation_code", "手术操作代码");
        FIELD_CN.put("incision_level", "切口等级");
        FIELD_CN.put("infection_site", "感染部位");
        FIELD_CN.put("pathogen", "病原菌");
        FIELD_CN.put("surgery_date", "关联手术日期");
        FIELD_CN.put("mdro_flag", "多重耐药菌标志");
        FIELD_CN.put("onset_date", "发病日期");
        FIELD_CN.put("age", "年龄");
        FIELD_CN.put("encounter_id", "就诊标识");
        for (Map.Entry<String, String> e : FIELD_CN.entrySet()) {
            CN_SORTED.add(Map.entry(e.getValue(), e.getKey()));
        }
        CN_SORTED.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
    }

    private static final String AND_GLUE = "，且 ";
    private static final String OR_GLUE = "，或 ";

    private HumanReadable() {}

    /** 业务中文名查询；未登记的字段保留原名（不伪造映射）。 */
    public static String fieldLabel(String field) {
        return FIELD_CN.getOrDefault(field, field);
    }

    // ---------------------------------------------------------------- 树 → 句

    public static String render(ConditionTree tree) {
        if (tree == null) {
            throw ExpressionErrors.error(ErrorCodes.E_RENDER_001, "reason", "条件树为空");
        }
        StringBuilder sb = new StringBuilder();
        writeNode(tree, sb);
        return sb.toString();
    }

    private static void writeNode(ConditionTree node, StringBuilder sb) {
        if (node.isLeaf()) {
            writeLeaf(node, sb);
            return;
        }
        String glue = "AND".equals(node.getOp()) ? AND_GLUE : OR_GLUE;
        List<ConditionTree> kids = node.getChildren();
        for (int i = 0; i < kids.size(); i++) {
            if (i > 0) sb.append(glue);
            ConditionTree kid = kids.get(i);
            boolean paren = !kid.isLeaf();
            if (paren) sb.append('(');
            writeNode(kid, sb);
            if (paren) sb.append(')');
        }
    }

    private static void writeLeaf(ConditionTree leaf, StringBuilder sb) {
        sb.append(fieldLabel(leaf.getField()));
        String cmp = leaf.getCmp();
        Object v = leaf.getValue();
        switch (cmp) {
            case "between": {
                List<?> l = asList(v);
                sb.append("在 ").append(renderScalar(l.isEmpty() ? "" : l.get(0)))
                  .append(" 至 ").append(renderScalar(l.size() > 1 ? l.get(1) : ""))
                  .append(" 之间");
                break;
            }
            case "in":
                sb.append("属于 ").append(renderSet(v));
                break;
            case "not_in":
                sb.append("不属于 ").append(renderSet(v));
                break;
            case "eq":
                sb.append("等于 ").append(renderScalar(v));
                break;
            case "ne":
                sb.append("不等于 ").append(renderScalar(v));
                break;
            case "gte":
                sb.append("大于等于 ").append(renderScalar(v));
                break;
            case "lte":
                sb.append("小于等于 ").append(renderScalar(v));
                break;
            case "gt":
                sb.append("大于 ").append(renderScalar(v));
                break;
            case "lt":
                sb.append("小于 ").append(renderScalar(v));
                break;
            case "contains":
                sb.append("包含 ").append(renderScalar(v));
                break;
            default:
                throw ExpressionErrors.error(ErrorCodes.E_RENDER_001, "cmp", cmp);
        }
    }

    private static String renderSet(Object v) {
        List<?> l = asList(v);
        if (l.isEmpty()) {
            return "空集合";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append('、');
            sb.append(renderScalar(l.get(i)));
        }
        return sb.toString();
    }

    private static String renderScalar(Object v) {
        if (v instanceof Boolean b) {
            return b ? "真" : "假";
        }
        if (v == null) {
            return "空";
        }
        return String.valueOf(v);
    }

    private static List<?> asList(Object v) {
        return v instanceof List<?> l ? l : List.of();
    }

    // ---------------------------------------------------------------- 句 → 树

    public static ConditionTree parse(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            throw ExpressionErrors.error(ErrorCodes.E_RENDER_001, "reason", "说明句为空");
        }
        return parseExpr(sentence.trim());
    }

    private static ConditionTree parseExpr(String raw) {
        String s = stripOuterParens(raw);
        if (s.isEmpty()) {
            throw ExpressionErrors.error(ErrorCodes.E_RENDER_001, "sentence", raw);
        }
        boolean hasAnd = splitTop(s, AND_GLUE).size() > 1;
        boolean hasOr = splitTop(s, OR_GLUE).size() > 1;
        if (hasAnd && hasOr) {
            // 顶层同时出现且/或且无括号限定 → 不猜优先级
            throw ExpressionErrors.error(ErrorCodes.E_RENDER_001,
                    "sentence", s, "reason", "同一层同时出现「且」与「或」，请用括号明确优先级");
        }
        if (hasOr) {
            List<ConditionTree> parts = new ArrayList<>();
            for (String p : splitTop(s, OR_GLUE)) {
                parts.add(parseExpr(p));
            }
            return ConditionTree.branch("OR", parts);
        }
        if (hasAnd) {
            List<ConditionTree> parts = new ArrayList<>();
            for (String p : splitTop(s, AND_GLUE)) {
                parts.add(parseExpr(p));
            }
            return ConditionTree.branch("AND", parts);
        }
        return parseLeaf(s);
    }

    private static ConditionTree parseLeaf(String s) {
        String field = null;
        int cnLen = 0;
        for (Map.Entry<String, String> e : CN_SORTED) {
            if (s.startsWith(e.getKey())) {
                field = e.getValue();
                cnLen = e.getKey().length();
                break;
            }
        }
        if (field == null) {
            throw ExpressionErrors.error(ErrorCodes.E_RENDER_001,
                    "sentence", s, "reason", "无法识别字段名（不在业务字段映射表内）");
        }
        String rest = s.substring(cnLen).trim();
        String cmp;
        Object value;
        if (rest.startsWith("在 ") && rest.endsWith(" 之间")) {
            cmp = "between";
            String inner = rest.substring(2, rest.length() - 3);
            String[] parts = inner.split(" 至 ", -1);
            if (parts.length != 2) {
                throw ExpressionErrors.error(ErrorCodes.E_RENDER_001, "sentence", s);
            }
            value = List.of(parseScalarText(parts[0].trim()), parseScalarText(parts[1].trim()));
        } else if (rest.startsWith("不属于 ")) {
            cmp = "not_in";
            value = parseSetText(rest.substring(4).trim(), s);
        } else if (rest.startsWith("属于 ")) {
            cmp = "in";
            value = parseSetText(rest.substring(3).trim(), s);
        } else if (rest.startsWith("不等于 ")) {
            cmp = "ne";
            value = parseScalarText(rest.substring(4).trim());
        } else if (rest.startsWith("等于 ")) {
            cmp = "eq";
            value = parseScalarText(rest.substring(3).trim());
        } else if (rest.startsWith("大于等于 ")) {
            cmp = "gte";
            value = parseScalarText(rest.substring(5).trim());
        } else if (rest.startsWith("小于等于 ")) {
            cmp = "lte";
            value = parseScalarText(rest.substring(5).trim());
        } else if (rest.startsWith("大于 ")) {
            cmp = "gt";
            value = parseScalarText(rest.substring(3).trim());
        } else if (rest.startsWith("小于 ")) {
            cmp = "lt";
            value = parseScalarText(rest.substring(3).trim());
        } else if (rest.startsWith("包含 ")) {
            cmp = "contains";
            value = parseScalarText(rest.substring(3).trim());
        } else {
            throw ExpressionErrors.error(ErrorCodes.E_RENDER_001,
                    "sentence", s, "reason", "无法识别的比较句式");
        }
        return ConditionTree.leaf(field, cmp, value);
    }

    private static List<Object> parseSetText(String t, String sentence) {
        if (t.isEmpty() || "空集合".equals(t)) {
            return new ArrayList<>();
        }
        List<Object> out = new ArrayList<>();
        for (String p : t.split("、", -1)) {
            out.add(parseScalarText(p.trim()));
        }
        return out;
    }

    private static Object parseScalarText(String t) {
        if ("真".equals(t)) return Boolean.TRUE;
        if ("假".equals(t)) return Boolean.FALSE;
        return t;
    }

    /** 在括号深度为 0 处按固定连接词切分。 */
    private static List<String> splitTop(String s, String sep) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        int last = s.length() - sep.length();
        while (i <= last) {
            char c = s.charAt(i);
            if (c == '(' || c == '（') {
                depth++;
            } else if (c == ')' || c == '）') {
                depth--;
            }
            if (depth == 0 && s.startsWith(sep, i)) {
                parts.add(s.substring(start, i).trim());
                i += sep.length();
                start = i;
            } else {
                i++;
            }
        }
        parts.add(s.substring(start).trim());
        return parts;
    }

    /** 仅当最外层括号包裹整个句子时去掉这对括号。 */
    private static String stripOuterParens(String s) {
        String cur = s.trim();
        while (cur.length() >= 2 && (cur.charAt(0) == '(' || cur.charAt(0) == '（')) {
            char open = cur.charAt(0);
            char close = open == '(' ? ')' : '）';
            if (cur.charAt(cur.length() - 1) != close || !wholeWrapped(cur)) {
                break;
            }
            cur = cur.substring(1, cur.length() - 1).trim();
        }
        return cur;
    }

    private static boolean wholeWrapped(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '（') {
                depth++;
            } else if (c == ')' || c == '）') {
                depth--;
                if (depth == 0 && i != s.length() - 1) {
                    return false; // 括号提前闭合，不是整体包裹
                }
            }
        }
        return depth == 0;
    }
}
