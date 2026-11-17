package com.infection.metric.app;

import com.infection.metric.common.EnvConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自检通道：配置项、凭据位、端点可达性与本地库可读写。
 *
 * <p>只做「读得到 / 连得上」的判定，失败时给可读原因；不打印令牌明文，不修改业务数据。</p>
 */
public final class DoctorRunner {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private DoctorRunner() {
    }

    /** 端点探针：只探测路径可达性，不带凭据。 */
    private static final Map<String, String[]> ENDPOINTS = new LinkedHashMap<>();

    static {
        ENDPOINTS.put("EMR_BASE_URL", new String[]{"/api/v1/metadata/fields"});
        ENDPOINTS.put("HAI_BASE_URL", new String[]{"/api/v1/infection-reports"});
        ENDPOINTS.put("QC_METRICS_BASE_URL", new String[]{"/api/v1/indicator-definitions"});
        ENDPOINTS.put("REGIONAL_PLATFORM_BASE_URL", new String[]{"/api/v1/indicator-packages/latest"});
    }

    public static void run() {
        EnvConfig config = EnvConfig.fromEnvironment();

        System.out.println("=== 院感指标口径影响分析 · 环境自检 ===");
        System.out.println();
        System.out.println("[1/3] 配置项与凭据位");
        for (String key : EnvConfig.REQUIRED_KEYS) {
            System.out.printf("      %-28s %s%n", key, config.describe(key));
        }
        System.out.println();

        System.out.println("[2/3] 端点可达性（不携带凭据）");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(PROBE_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        for (Map.Entry<String, String[]> entry : ENDPOINTS.entrySet()) {
            String baseUrl = config.get(entry.getKey());
            String path = entry.getValue()[0];
            if (baseUrl == null) {
                System.out.printf("      %-28s 未配置地址，跳过探测%n", entry.getKey());
                continue;
            }
            System.out.printf("      %-28s %s%n", entry.getKey(), probe(client, baseUrl, path));
        }
        System.out.println();

        System.out.println("[3/3] 本地口径库可读写");
        for (Path path : localPaths()) {
            System.out.printf("      %-28s %s%n", path, writable(path));
        }
        System.out.println();

        java.util.List<String> missing = config.missing();
        if (missing.isEmpty()) {
            System.out.println("自检结论：配置项齐全。");
        } else {
            System.out.println("自检结论：缺少 " + missing.size() + " 项配置 —— " + String.join("、", missing));
            System.out.println("           复制 .env.example 为 .env 后填写；令牌不会写入日志。");
        }
    }

    private static java.util.List<Path> localPaths() {
        return java.util.List.of(
                Paths.get("data", "library"),
                Paths.get("data", "runs"),
                Paths.get("out", "impact"));
    }

    private static String probe(HttpClient client, String baseUrl, String path) {
        String target = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + path : baseUrl + path;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return "可达（HTTP " + status + "）";
            }
            if (status == 401 || status == 403) {
                return "可达（HTTP " + status + "，未授权，凭据位缺失或无效）";
            }
            return "可达（HTTP " + status + "）";
        } catch (IOException e) {
            return "不可达：" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "探测被中断";
        } catch (RuntimeException e) {
            return "不可达：地址或路径不合法（" + e.getMessage() + "）";
        }
    }

    private static String writable(Path path) {
        try {
            if (Files.exists(path)) {
                return Files.isWritable(path) ? "可读写" : "存在但不可写";
            }
            Path parent = path.getParent() == null ? path : path.getParent();
            if (Files.exists(parent)) {
                return Files.isWritable(parent) ? "可写（尚未创建）" : "父目录不可写";
            }
            return "尚未创建（首次运行时自动生成）";
        } catch (RuntimeException e) {
            return "检查失败：" + e.getMessage();
        }
    }
}
