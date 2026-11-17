package com.infection.metric.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infection.metric.recompute.RecomputeResult;
import com.infection.metric.recompute.RunSummary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 离线演示通道：读仓库内置的脱敏样例，打印可核对的样例构成。
 *
 * <p>此通道为<b>显式开关</b>的演示路径，<b>不是</b>默认启动路径；默认启动指向真实院内端点。
 * 与 {@code --headless} 组合时走 {@link #runRecompute()}，真跑一遍复算并落盘全部产物。</p>
 */
public final class OfflineSampleRunner {

    private OfflineSampleRunner() {
    }

    private static final String RESOURCE = "/samples/offline-sample.json";

    public static void run() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = OfflineSampleRunner.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                System.err.println("[离线演示] 内置样例缺失：" + RESOURCE);
                System.exit(2);
                return;
            }
            JsonNode sample = mapper.readTree(in);

            System.out.println("=== 离线演示 · 脱敏最小样例 ===");
            System.out.println("样例标识：" + sample.path("sample_id").asText(""));
            System.out.println("来源标注：" + sample.path("provenance").asText(""));
            System.out.println("口径条目：" + sample.path("indicators").size() + " 条");
            System.out.println("出院记录：" + sample.path("encounters").size() + " 条");
            System.out.println("院感明细：" + sample.path("infection_reports").size() + " 条");
            System.out.println();

            System.out.println("口径清单（编码 / 版本 / 状态 / 名称）：");
            for (JsonNode indicator : sample.path("indicators")) {
                System.out.printf("      %-14s %-10s %-16s %s%n",
                        indicator.path("indicator_code").asText(""),
                        indicator.path("definition_version").asText(""),
                        indicator.path("status").asText(""),
                        indicator.path("title").asText(""));
            }

            Path outDir = Paths.get("out", "offline-sample");
            Files.createDirectories(outDir);
            Path copy = outDir.resolve("offline-sample-summary.txt");
            Files.writeString(copy, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(sample.path("indicators")), StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("样例口径清单落盘：" + copy);
            System.out.println("提示：此通道仅作离线演示；默认启动路径指向真实院内端点，不使用本地样例。");
        }
    }

    /**
     * {@code ./run.sh --offline-sample --headless}：用规则化合成数据真跑复算、影响面与双版本对拍，
     * 回显全部可核对数字并落盘运行摘要。不编造任何数字，失败项逐条显式列出。
     */
    public static void runRecompute() throws IOException {
        OfflineSampleArtifacts.Snapshot s =
                OfflineSampleArtifacts.computeAndPersist(Paths.get("data"));
        RunSummary summary = s.summary;

        System.out.println("=== 离线演示 · 真实复算与运行摘要 ===");
        System.out.println("数据源版本：" + summary.getDataSourceVersion());
        System.out.println("口径：" + s.dataset.indicators().size() + " 条（官方基准 "
                + s.dataset.officialCount() + " / 本院脱敏草稿 " + s.dataset.draftCount() + "）");
        System.out.println("出院记录：" + s.dataset.encountersAll().size() + " 条　院感明细："
                + s.dataset.infectionsAll().size() + " 条　oracle：" + s.dataset.oracleRowCount() + " 行");
        System.out.println("复算规模：" + s.dataset.indicators().size() + " 指标 × "
                + s.dataset.quarters().size() + " 季度 = " + summary.getResults().size() + " 次求值");
        System.out.println("oracle 对照：一致 " + (summary.getResults().size() - summary.getFailedSteps().size())
                + " 项，失败/不一致 " + summary.getFailedSteps().size() + " 项");
        System.out.println("取数耗时：" + summary.getFetchMillis() + " ms　计算耗时："
                + summary.getComputeMillis() + " ms");

        System.out.println();
        System.out.println("复算抽样（前 5 条，分子 / 分母）：");
        int shown = 0;
        for (RecomputeResult r : summary.getResults()) {
            if (shown++ >= 5) break;
            System.out.printf("      %-14s %-8s %-8s 分子 %-6s 分母 %-6s %s%n",
                    r.getIndicatorCode(), r.getQuarter(), r.getDefinitionVersion(),
                    r.getNumerator() == null ? "失败" : r.getNumerator(),
                    r.getDenominator() == null ? "失败" : r.getDenominator(),
                    r.getStatus());
        }
        if (!summary.getFailedSteps().isEmpty()) {
            System.out.println("失败步骤（不按 0 计入分母）：");
            for (RunSummary.FailedStep f : summary.getFailedSteps()) {
                System.out.println("      " + f.getIndicatorCode() + " / " + f.getQuarter()
                        + " / " + f.getStep() + "：" + f.getReason());
            }
        }

        System.out.println();
        System.out.println("影响面：变更 " + OfflineSampleArtifacts.CHANGED_CODE
                + " → 受影响 " + s.impact.getAffected().size() + " 项；需重算季度 "
                + s.impact.recomputeQuartersText());
        System.out.println("差异说明三段（" + s.quarter + "，新旧两版各复算一遍）：");
        s.segments.forEach(seg -> System.out.println("      " + seg.getName() + "：" + seg.getNumber()));
        System.out.println();
        System.out.println("运行标识：" + s.resultId);
        System.out.println("落库季度：" + String.join("、", summary.getLandedQuarters()));
        System.out.println("运行摘要：" + OfflineSampleArtifacts.RECOMPUTE);
        System.out.println("影响清单：" + s.impactListPath);
        System.out.println("差异说明：" + OfflineSampleArtifacts.DIFF);
    }
}
