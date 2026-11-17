package com.infection.metric.recompute;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.JsonSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * 差异说明三段渲染器（任务35）：把双版本复算对拍结果组装成
 * 《口径变更影响与重算差异说明》的分子差 / 分母差 / 口径差三段。
 *
 * <p>每段带数字与来源引用（口径编码 + 版本 + 变更条目）；渲染为中文段落与表格两种形态。
 * 纯函数：同一输入两次渲染结果逐字一致（不含时间戳类字段）。
 * 缺一段时显式抛 E_RENDER_001，绝不静默留空。</p>
 */
public final class DiffReportRenderer {

    /** 单个差异段：段名 + 数字 + 说明行 + 来源引用。 */
    public static final class Segment {
        private final String name;
        private final int number;
        private final List<String> lines = new ArrayList<>();

        Segment(String name, int number) {
            this.name = name;
            this.number = number;
        }

        void addLine(String line) { lines.add(line); }

        public String getName() { return name; }
        public int getNumber() { return number; }
        public List<String> getLines() { return List.copyOf(lines); }
    }

    /** 三段组装与校验：缺一段即报错，不留空。 */
    public List<Segment> segments(RecomputeComparison cmp) {
        validate(cmp);
        List<Segment> out = new ArrayList<>();
        String src = sourceRef(cmp);
        Integer numDelta = cmp.getNumeratorDelta();
        Integer denDelta = cmp.getDenominatorDelta();
        DiffResult diff = cmp.getDefinitionDiff();

        Segment numerator = new Segment("分子差", numDelta);
        numerator.addLine("本期（新版 " + cmp.getNewVersion() + "）分子 "
                + cmp.getNewResult().getNumerator() + "，旧版 " + cmp.getOldVersion() + " 分子 "
                + cmp.getOldResult().getNumerator() + "，分子差 " + signed(numDelta) + "。");
        numerator.addLine("来源：" + src + "。");
        out.add(numerator);

        Segment denominator = new Segment("分母差", denDelta);
        denominator.addLine("本期（新版 " + cmp.getNewVersion() + "）分母 "
                + cmp.getNewResult().getDenominator() + "，旧版 " + cmp.getOldVersion() + " 分母 "
                + cmp.getOldResult().getDenominator() + "，分母差 " + signed(denDelta) + "。");
        denominator.addLine("来源：" + src + "。");
        out.add(denominator);

        Segment definition = new Segment("口径差", diff.total());
        if (diff.isEmpty()) {
            definition.addLine("新旧两版口径条件级无实质变更（仅版本由 "
                    + cmp.getOldVersion() + " 升至 " + cmp.getNewVersion() + "）。");
        } else {
            definition.addLine("条件级差异共 " + diff.total() + " 条：");
            int idx = 1;
            for (ConditionChange c : diff.all()) {
                definition.addLine((idx++) + ". " + c.renderCn());
            }
        }
        definition.addLine("来源：" + src + "。");
        out.add(definition);
        return out;
    }

    /** 中文段落形态（用于上报材料正文）。 */
    public String renderParagraphs(RecomputeComparison cmp) {
        List<Segment> segs = segments(cmp);
        StringBuilder sb = new StringBuilder();
        sb.append("《口径变更影响与重算差异说明》\n");
        sb.append("指标：").append(cmp.getIndicatorCode())
                .append("　季度：").append(cmp.getQuarter())
                .append("　口径版本：").append(cmp.getOldVersion())
                .append(" → ").append(cmp.getNewVersion()).append("\n\n");
        String[] ordinal = {"一", "二", "三"};
        for (int i = 0; i < segs.size(); i++) {
            Segment s = segs.get(i);
            sb.append(ordinal[i]).append("、").append(s.getName()).append("\n");
            for (String line : s.getLines()) {
                sb.append("  ").append(line).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 中文表格形态（用于附件核对）。 */
    public String renderTable(RecomputeComparison cmp) {
        List<Segment> segs = segments(cmp);
        String src = sourceRef(cmp);
        StringBuilder sb = new StringBuilder();
        sb.append("《口径变更影响与重算差异说明》核对表　指标：")
                .append(cmp.getIndicatorCode()).append("　季度：").append(cmp.getQuarter()).append("\n");
        sb.append("| 段 | 旧值 | 新值 | 差值 | 来源引用 |\n");
        sb.append("| --- | --- | --- | --- | --- |\n");
        sb.append("| 分子差 | ").append(cmp.getOldResult().getNumerator())
                .append(" | ").append(cmp.getNewResult().getNumerator())
                .append(" | ").append(signed(cmp.getNumeratorDelta()))
                .append(" | ").append(src).append(" |\n");
        sb.append("| 分母差 | ").append(cmp.getOldResult().getDenominator())
                .append(" | ").append(cmp.getNewResult().getDenominator())
                .append(" | ").append(signed(cmp.getDenominatorDelta()))
                .append(" | ").append(src).append(" |\n");
        sb.append("| 口径差 | - | - | ").append(cmp.getDefinitionDiff().total())
                .append(" | ").append(src).append(" |\n");
        sb.append("\n| 序号 | 桶 | 位置 | 字段 | 旧值 | 新值 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");
        int idx = 1;
        for (ConditionChange c : cmp.getDefinitionDiff().all()) {
            sb.append("| ").append(idx++).append(" | ").append(c.getBucket().getCn())
                    .append(" | ").append(c.getPosition().getCn())
                    .append(" | ").append(c.getField())
                    .append(" | ").append(c.getOldValue() == null ? "（无）" : c.getOldValue())
                    .append(" | ").append(c.getNewValue() == null ? "（无）" : c.getNewValue())
                    .append(" |\n");
        }
        return sb.toString();
    }

    /** 三段结构化 JSON（供界面 / 归档核对）。 */
    public ObjectNode renderJson(RecomputeComparison cmp) {
        List<Segment> segs = segments(cmp);
        ObjectNode o = JsonSupport.object();
        o.put("indicator_code", cmp.getIndicatorCode());
        o.put("old_version", cmp.getOldVersion());
        o.put("new_version", cmp.getNewVersion());
        o.put("quarter", cmp.getQuarter());
        ArrayNode arr = o.putArray("segments");
        for (Segment s : segs) {
            ObjectNode e = arr.addObject();
            e.put("name", s.getName());
            e.put("number", s.getNumber());
            ArrayNode lines = e.putArray("lines");
            for (String line : s.getLines()) lines.add(line);
        }
        return o;
    }

    private void validate(RecomputeComparison cmp) {
        if (cmp == null) {
            throw new AppException(ErrorCodes.E_RENDER_001,
                    "差异说明三段不完整：缺少双版本复算对拍结果");
        }
        if (cmp.getDefinitionDiff() == null) {
            throw new AppException(ErrorCodes.E_RENDER_001,
                    "差异说明三段不完整：缺少口径差段（条件级差异）");
        }
        if (cmp.getOldResult() == null || cmp.getNewResult() == null
                || !cmp.getOldResult().hasValidNumbers()
                || !cmp.getNewResult().hasValidNumbers()) {
            throw new AppException(ErrorCodes.E_RENDER_001,
                    "差异说明三段不完整：缺少分子差 / 分母差段的复算数字（失败不得按 0 留空）");
        }
        if (!cmp.hasAllSegments()) {
            throw new AppException(ErrorCodes.E_RENDER_001,
                    "差异说明三段不完整：三段数据未全部就绪");
        }
    }

    private static String sourceRef(RecomputeComparison cmp) {
        return "口径编码 " + cmp.getIndicatorCode()
                + "，旧版 " + cmp.getOldVersion()
                + "，新版 " + cmp.getNewVersion()
                + "，变更条目 " + cmp.getDefinitionDiff().total() + " 条";
    }

    private static String signed(Integer d) {
        if (d == null) return "（缺失）";
        return d >= 0 ? "+" + d : String.valueOf(d);
    }
}
