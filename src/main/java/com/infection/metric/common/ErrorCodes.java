package com.infection.metric.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 错误码与中文可读文案表。所有失败路径走这里，渲染时带上下文变量。 */
public final class ErrorCodes {

    public static final String E_PARSE_001 = "E_PARSE_001";
    public static final String E_PARSE_002 = "E_PARSE_002";
    public static final String E_PARSE_003 = "E_PARSE_003";
    public static final String E_TYPE_002 = "E_TYPE_002";
    public static final String E_TYPE_003 = "E_TYPE_003";
    public static final String E_FIELD_001 = "E_FIELD_001";
    public static final String E_DATA_001 = "E_DATA_001";
    public static final String E_DATA_002 = "E_DATA_002";
    public static final String E_LEX_001 = "E_LEX_001";
    public static final String E_LEX_002 = "E_LEX_002";
    public static final String E_SEM_001 = "E_SEM_001";
    public static final String E_SEM_002 = "E_SEM_002";
    public static final String E_RENDER_001 = "E_RENDER_001";
    public static final String E_STATE_001 = "E_STATE_001";
    public static final String E_CONFLICT_001 = "E_CONFLICT_001";
    public static final String E_STORE_001 = "E_STORE_001";
    public static final String E_GRAPH_001 = "E_GRAPH_001";
    public static final String E_GRAPH_002 = "E_GRAPH_002";
    public static final String E_GRAPH_003 = "E_GRAPH_003";
    public static final String E_GRAPH_004 = "E_GRAPH_004";
    public static final String E_GRAPH_005 = "E_GRAPH_005";
    public static final String E_GRAPH_006 = "E_GRAPH_006";
    public static final String E_EDGE_001 = "E_EDGE_001";
    public static final String E_RECOMPUTE_001 = "E_RECOMPUTE_001";
    public static final String E_RECOMPUTE_002 = "E_RECOMPUTE_002";
    public static final String E_OUTBOUND_001 = "E_OUTBOUND_001";
    public static final String E_OUTBOUND_002 = "E_OUTBOUND_002";
    public static final String E_OUTBOUND_003 = "E_OUTBOUND_003";
    public static final String E_OUTBOUND_004 = "E_OUTBOUND_004";
    public static final String E_LLM_001 = "E_LLM_001";
    public static final String E_LLM_002 = "E_LLM_002";
    public static final String E_LLM_003 = "E_LLM_003";
    public static final String E_LLM_004 = "E_LLM_004";
    public static final String E_LLM_005 = "E_LLM_005";
    public static final String E_PKG_001 = "E_PKG_001";
    public static final String E_PKG_002 = "E_PKG_002";
    public static final String E_PKG_003 = "E_PKG_003";
    public static final String E_AUDIT_001 = "E_AUDIT_001";
    public static final String E_PUBLISH_001 = "E_PUBLISH_001";

    private static final Map<String, String> TEXT = new LinkedHashMap<>();
    private static final Map<String, String> HINT = new LinkedHashMap<>();

    static {
        def(E_PARSE_001, "口径表达式期望右括号但已到结尾", "补齐右括号 ) 后重试");
        def(E_PARSE_002, "未知比较符 {cmp}", "比较符只能用 eq/ne/in/not_in/between/gte/lte/gt/lt/contains");
        def(E_PARSE_003, "括号或花括号不匹配", "检查 [ ] 与 { } 是否成对出现");
        def(E_TYPE_002, "字段值类型不符（字段 {field}）", "按字段字典声明的类型填写取值");
        def(E_TYPE_003, "比较符 {cmp} 与值类型不匹配", "between 需 [lo,hi]，in/not_in 需列表，contains 需字符串");
        def(E_FIELD_001, "口径 {code} 声明依赖字段 {field}，数据源 {source} 未提供该字段", "核对字段字典或在口径中改用已有字段");
        def(E_DATA_001, "记录缺少取值（字段 {field}）", "补齐该字段取值；不允许按 0 静默计入分母");
        def(E_DATA_002, "取值超长（字段 {field}）", "缩短取值或调整字典长度上限");
        def(E_LEX_001, "字符串未闭合，起始于 {position}", "补上成对引号");
        def(E_LEX_002, "非法字符 {char}", "删除该字符或使用转义写法");
        def(E_SEM_001, "区间上下界颠倒", "把 between 的下界放在前面、上界放在后面");
        def(E_SEM_002, "数值与字符串类型混排", "同一集合内只放一种类型的值");
        def(E_RENDER_001, "无法识别的说明句，不做猜测解析", "使用受支持的人话句式模板");
        def(E_STATE_001, "非法状态跃迁：{from} → {to}", "按状态机允许的下一步推进");
        def(E_CONFLICT_001, "主键 {key} 已存在且内容不同", "改用新版本号，或先核对差异后再提交");
        def(E_STORE_001, "口径库索引 index.json 缺失或损坏", "恢复索引或重建年度分区，不静默返回空库");
        def(E_GRAPH_001, "口径依赖成环：{chain}", "断开环上任一依赖边后重建依赖图");
        def(E_GRAPH_002, "悬空边：引用不存在的口径 {target}", "补齐被引用口径或删除该依赖边");
        def(E_GRAPH_003, "依赖图尚未 build，不能查询", "先调用 build() 再查询");
        def(E_GRAPH_004, "查询未知口径 ID {id}", "核对指标编码与版本号");
        def(E_GRAPH_005, "展开超预算：节点 {actual} 超过上限 {limit}", "缩小范围或显式提高预算上限");
        def(E_GRAPH_006, "展开深度 {depth} 非法", "合法范围为 1 至上限层数");
        def(E_EDGE_001, "依赖边缺少依据 evidence，不允许写入", "为每条依赖边写明人话依据与口径指纹");
        def(E_RECOMPUTE_001, "取数失败或字段缺失（指标 {code}）", "修复数据源后重跑；本次复算显式失败");
        def(E_RECOMPUTE_002, "口径 {code} 未发布，不可用于复算", "先经主任确认发布新版本");
        def(E_OUTBOUND_001, "出站连接失败（{endpoint}）", "检查地址与网络后重试");
        def(E_OUTBOUND_002, "出站请求超时（{endpoint}）", "稍后重试或调大超时配置");
        def(E_OUTBOUND_003, "服务端返回 4xx（HTTP {status}）", "核对请求参数与凭据；4xx 不重试");
        def(E_OUTBOUND_004, "服务端返回 5xx（HTTP {status}）", "将按指数退避重试，超过上限后显式失败");
        def(E_LLM_001, "模型服务连接失败", "检查 LLM_BASE_URL 与网络");
        def(E_LLM_002, "模型调用超时", "调大 LLM_TIMEOUT 或稍后重试");
        def(E_LLM_003, "模型响应非 JSON", "检查服务端返回内容；不做猜测修复");
        def(E_LLM_004, "模型输出 schema 校验失败", "不合格输出一律失败且不重试");
        def(E_LLM_005, "模型返回空结果", "退回人工录入条件树");
        def(E_PKG_001, "不支持的口径包格式 {format}", "当前支持 csv / json；可用 Excel 另存为 CSV UTF-8 后再导入");
        def(E_PKG_002, "口径包状态非法跃迁：{from} → {to}", "按 IMPORTED → REVIEWED → MERGED 顺序推进");
        def(E_PKG_003, "该口径包已合并，重复导入被拒绝", "查看已有合并回执，无需重复合并");
        def(E_AUDIT_001, "审批前置条件不满足：{reason}", "完成前置动作后再提交");
        def(E_PUBLISH_001, "发布前置校验未通过：{reason}", "状态、字段对账、依赖图三项须全部通过");
    }

    private ErrorCodes() {}

    private static void def(String code, String text, String hint) {
        TEXT.put(code, text);
        HINT.put(code, hint);
    }

    public static Set<String> all() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(TEXT.keySet()));
    }

    public static boolean isKnown(String code) { return TEXT.containsKey(code); }

    public static String text(String code) {
        return TEXT.getOrDefault(code, "未知错误");
    }

    public static String hint(String code) {
        return HINT.getOrDefault(code, "请联系信息科排查");
    }

    /** 用上下文变量替换文案中的 {xxx} 占位符。 */
    public static String render(String code, Map<String, String> ctx) {
        String out = text(code);
        if (ctx != null) {
            for (Map.Entry<String, String> e : ctx.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return out;
    }

    public static AppException error(String code) {
        return error(code, Collections.emptyMap());
    }

    public static AppException error(String code, Map<String, String> ctx) {
        Map<String, String> safe = ctx == null ? Collections.emptyMap() : ctx;
        return new AppException(code, render(code, safe), null, null, null,
                hint(code), safe, null);
    }

    /** 变长键值对：error(code, "field", "dept_code", "source", "EMR")。 */
    public static AppException error(String code, String... pairs) {
        Map<String, String> ctx = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            ctx.put(pairs[i], pairs[i + 1]);
        }
        return error(code, ctx);
    }
}
