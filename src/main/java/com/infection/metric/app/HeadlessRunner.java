package com.infection.metric.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 无界面复算通道：跑一次口径库扫描，产出《复算运行摘要》。
 *
 * <p>本任务只搭通道骨架：真实读取本地口径库、真实写运行摘要。复算引擎本体在 M4 接入；
 * 在引擎接入前，本通道显式报告「无可复算的已发布口径」，绝不编造分子分母数字。</p>
 */
public final class HeadlessRunner {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private HeadlessRunner() {
    }

    public static void run() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Path libraryRoot = Paths.get("data", "library");
        Path runRoot = Paths.get("out", "runs", String.valueOf(LocalDateTime.now().getYear()));

        List<ObjectNode> published = new ArrayList<>();
        List<ObjectNode> drafts = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();

        if (Files.isDirectory(libraryRoot)) {
            try (Stream<Path> files = Files.walk(libraryRoot)) {
                List<Path> definitions = files
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !p.getFileName().toString().equals("index.json"))
                        .sorted()
                        .toList();
                for (Path definition : definitions) {
                    try {
                        ObjectNode node = (ObjectNode) mapper.readTree(definition.toFile());
                        String status = node.path("status").asText("");
                        if ("PUBLISHED".equals(status)) {
                            published.add(node);
                        } else {
                            drafts.add(node);
                        }
                    } catch (IOException e) {
                        unreadable.add(definition + "：" + e.getMessage());
                    }
                }
            }
        }

        String runId = "run-" + LocalDateTime.now().format(STAMP);
        Path runDir = runRoot.resolve(runId);
        Files.createDirectories(runDir);

        ObjectNode summary = mapper.createObjectNode();
        summary.put("run_id", runId);
        summary.put("started_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        summary.put("mode", "headless");
        summary.put("library_root", libraryRoot.toString());
        summary.put("published_count", published.size());
        summary.put("draft_count", drafts.size());

        ArrayNode computed = mapper.createArrayNode();
        for (ObjectNode node : published) {
            ObjectNode item = mapper.createObjectNode();
            item.put("indicator_code", node.path("indicator_code").asText(""));
            item.put("definition_version", node.path("definition_version").asText(""));
            item.put("status", "已发布口径已识别；复算引擎待 M4 接入");
            computed.add(item);
        }
        summary.set("indicators", computed);

        ArrayNode failures = mapper.createArrayNode();
        for (String message : unreadable) {
            failures.add(message);
        }
        if (published.isEmpty()) {
            failures.add("本地口径库中无已发布口径，本次复算未执行（0 项）；复算引擎待 M4 接入");
        }
        summary.set("failed_steps", failures);

        ArrayNode unpublished = mapper.createArrayNode();
        for (ObjectNode node : drafts) {
            unpublished.add(node.path("indicator_code").asText("") + " / " + node.path("definition_version").asText(""));
        }
        summary.set("unpublished_indicators", unpublished);
        summary.put("result_quarter", "未落库（无已发布口径）");

        Path summaryPath = runDir.resolve("summary.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(summaryPath.toFile(), summary);

        System.out.println("=== 复算运行摘要 ===");
        System.out.println("运行标识：" + runId);
        System.out.println("已发布口径：" + published.size() + " 条　草稿口径：" + drafts.size() + " 条");
        System.out.println("失败步骤：" + (unreadable.isEmpty() && !published.isEmpty() ? "无" : String.valueOf(failures.size()) + " 项"));
        System.out.println("摘要落盘：" + summaryPath.toString());
        if (published.isEmpty()) {
            System.out.println("说明：本地口径库为空或无已发布口径，未产生任何复算数字（不按 0 充数）。");
        }
    }
}
