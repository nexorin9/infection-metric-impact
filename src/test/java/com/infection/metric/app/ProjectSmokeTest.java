package com.infection.metric.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infection.metric.common.EnvConfig;
import com.infection.metric.common.Hashes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冒烟测试：保证工程可构建、可装载关键配置面、内置离线样例可解析。
 * 业务断言在 M1–M8 各模块的单测中逐步补齐。
 */
class ProjectSmokeTest {

    @Test
    @DisplayName("入口的四种运行模式互不重叠")
    void entryModesAreDistinct() {
        String[] modes = {
                AppLauncher.MODE_HEADLESS,
                AppLauncher.MODE_DOCTOR,
                AppLauncher.MODE_OFFLINE_SAMPLE,
                AppLauncher.MODE_HELP
        };
        assertEquals(modes.length, java.util.Set.of(modes).size());
    }

    @Test
    @DisplayName("配置面列出全部 13 项环境变量，且未配置项可被点名")
    void envConfigListsRequiredKeys() {
        assertEquals(13, EnvConfig.REQUIRED_KEYS.size());
        EnvConfig config = EnvConfig.fromEnvironment();
        assertNotNull(config.missing());
        for (String key : EnvConfig.REQUIRED_KEYS) {
            assertFalse(config.describe(key).isBlank(), "配置项描述不得为空：" + key);
        }
        // 令牌即便已配置也不得回显明文。
        for (String key : EnvConfig.REQUIRED_KEYS) {
            if (key.endsWith("_TOKEN") || key.endsWith("_API_KEY")) {
                assertTrue(config.describe(key).startsWith("已配置") || config.describe(key).equals("未配置"));
            }
        }
    }

    @Test
    @DisplayName("内容哈希：同内容同哈希，不同内容不同哈希")
    void hashesAreStableAndDiscriminating() {
        assertEquals(Hashes.sha256Of("HAI-INC-01", "2026.1"), Hashes.sha256Of("HAI-INC-01", "2026.1"));
        assertEquals(64, Hashes.sha256Of("HAI-INC-01", "2026.1").length());
        assertNotEquals(Hashes.sha256Of("HAI-INC-01", "2026.1"), Hashes.sha256Of("HAI-INC-01", "2026.2"));
        // 幂等键两种取值域互不相同
        assertNotEquals(Hashes.sha256Of("HAI-INC-01", "2026.1"),
                Hashes.sha256Of("pkg-2026-03", "2026.4"));
    }

    @Test
    @DisplayName("内置离线样例可解析：3 条口径 / 4 条出院记录 / 2 条院感明细")
    void bundledOfflineSampleIsLoadable() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/samples/offline-sample.json")) {
            assertNotNull(in, "内置离线样例缺失");
            JsonNode sample = new ObjectMapper().readTree(in);
            assertEquals("offline-sample-001", sample.path("sample_id").asText());
            assertEquals(3, sample.path("indicators").size());
            assertEquals(4, sample.path("encounters").size());
            assertEquals(2, sample.path("infection_reports").size());
            // 样例内不得出现患者可识别明细字段
            for (JsonNode encounter : sample.path("encounters")) {
                assertFalse(encounter.has("patient_name"), "样例不得含患者姓名");
                assertFalse(encounter.has("id_card"), "样例不得含证件号");
            }
        }
    }
}
