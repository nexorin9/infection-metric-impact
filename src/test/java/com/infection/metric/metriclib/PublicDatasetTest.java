package com.infection.metric.metriclib;

import com.infection.metric.common.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务20：离线降级 manifest 的来源标注、规模、字段映射与完整性校验。 */
class PublicDatasetTest {

    @TempDir Path tempDir;

    /** 与发布清理无关：测试自建一份降级 manifest，不依赖 data/public/manifest.json 存在。 */
    private static final String DEGRADED_MANIFEST = """
            {
              "source_name": "Synthea 公开样例 CSV",
              "source_url": "https://example.invalid/encounters.csv",
              "license": "Apache-2.0",
              "fetched_on": "2026-11-09",
              "min_rows": 1000,
              "field_mapping": {
                "encounter_id": "就诊标识",
                "discharge_date": "出院日期",
                "dept_code": "科室代码",
                "primary_diag_code": "主要诊断代码",
                "procedure_code": "手术操作代码",
                "wound_class": "切口等级"
              },
              "provenance": "bundled-minimal",
              "file": "public-sample.csv",
              "sha256": "8063aac05bad9e4fe88a24564ed58a3db23eb1152e7a1ea1f32a7e3d26e10aa5",
              "rows": 1200,
              "columns": ["encounter_id", "discharge_date", "dept_code", "primary_diag_code", "procedure_code", "wound_class"],
              "status": "degraded",
              "reason": "显式 --offline 模拟断网",
              "note": "内置最小样例仅用于离线自检"
            }
            """;

    private Path writeManifest(String content) throws IOException {
        Path p = tempDir.resolve("manifest.json");
        Files.writeString(p, content);
        return p;
    }

    @Test
    void 离线降级清单来源标注行数与字段映射正确() throws IOException {
        PublicDatasetManifest m = PublicDatasetManifest.load(writeManifest(DEGRADED_MANIFEST));

        assertEquals("bundled-minimal", m.provenance());
        assertEquals(1200, m.rows());
        assertTrue(m.rows() >= 1000);
        assertTrue(m.isDegraded());
        assertEquals("degraded", m.status(), "断网降级不得假装下载成功");
        assertEquals(64, m.sha256().length());

        assertTrue(m.columns().contains("discharge_date"));
        assertTrue(m.columns().contains("primary_diag_code"));
        assertEquals("就诊标识", m.fieldMapping().get("encounter_id"));
        assertEquals("出院日期", m.fieldMapping().get("discharge_date"));
        assertEquals("科室代码", m.fieldMapping().get("dept_code"));
        assertEquals("主要诊断代码", m.fieldMapping().get("primary_diag_code"));
        assertDoesNotThrow(m::validate);
    }

    @Test
    void classpath内置样例本身行数与列名达标() {
        PublicDatasetManifest m = PublicDatasetManifest.fromBundledSample();

        assertEquals(1200, m.rows());
        assertTrue(m.rows() >= 1000);
        assertEquals(6, m.columns().size());
        assertTrue(m.columns().containsAll(
                List.of("encounter_id", "discharge_date", "dept_code", "primary_diag_code")));
        assertEquals("bundled-minimal", m.provenance());
        assertTrue(m.isDegraded());
        assertEquals(64, m.sha256().length(), "sha256 应由样例内容现场算出");
        assertDoesNotThrow(m::validate);
    }

    @Test
    void 行数低于下限validate显式报错() throws IOException {
        String bad = DEGRADED_MANIFEST.replace("\"rows\": 1200", "\"rows\": 12");
        PublicDatasetManifest m = PublicDatasetManifest.load(writeManifest(bad));

        assertEquals(12, m.rows());
        AppException ex = assertThrows(AppException.class, m::validate);
        assertEquals("E_DATA_001", ex.getCode());
        assertTrue(ex.render().contains("1000"));
    }

    @Test
    void 字段映射缺出院记录字段validate显式报错() throws IOException {
        String bad = DEGRADED_MANIFEST.replace("\"dept_code\": \"科室代码\",", "");
        PublicDatasetManifest m = PublicDatasetManifest.load(writeManifest(bad));

        assertFalse(m.fieldMapping().containsValue("科室代码"));
        AppException ex = assertThrows(AppException.class, m::validate);
        assertEquals("E_DATA_001", ex.getCode());
        assertTrue(ex.render().contains("科室代码"));
    }

    @Test
    void 清单缺失显式失败且无参load不依赖本地清单() {
        AppException ex = assertThrows(AppException.class,
                () -> PublicDatasetManifest.load(tempDir.resolve("missing-manifest.json")));
        assertEquals("E_DATA_001", ex.getCode());
        assertTrue(ex.render().contains("manifest.json"));

        PublicDatasetManifest m = PublicDatasetManifest.load();
        assertTrue(m.rows() >= 1000);
        assertTrue(m.columns().contains("discharge_date"));
        assertTrue(m.isDegraded());
        assertDoesNotThrow(m::validate);
    }
}
