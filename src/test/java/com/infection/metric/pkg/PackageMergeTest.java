package com.infection.metric.pkg;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.Hashes;
import com.infection.metric.common.JsonSupport;
import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;
import com.infection.metric.common.OutboundTransport;
import com.infection.metric.expr.ConditionTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务49：口径包合并回写、幂等键与重复导入保护。 */
class PackageMergeTest {

    @TempDir
    Path temp;

    /** 内存替身：记录请求形状，返回可控回执，不发起真实网络。 */
    private static final class RecordingTransport implements OutboundTransport {
        final List<OutboundRequest> requests = new ArrayList<>();
        final OutboundResponse response = OutboundResponse.of(200, "{\"id\":\"merge-001\"}");

        @Override
        public OutboundResponse send(OutboundRequest request) {
            requests.add(request);
            return response;
        }
    }

    private static final String PV = "PKG-2026Q1";
    private static final String TV = "v2";
    private static final String BASE = "https://qc.example.com";

    private static PackageEntry entry(String no) {
        ConditionTree num = ConditionTree.leaf("dept_code", "eq", "0301");
        ConditionTree den = ConditionTree.leaf("discharge_flag", "eq", "1");
        return PackageEntry.builder()
                .entryNo(no).indicatorCode("HAI-" + no).indicatorName("指标 " + no)
                .definition("口径定义 " + no)
                .numeratorTree(num).denominatorTree(den)
                .numerator("num").denominator("den")
                .deptCodes(List.of("0301"))
                .effectiveFrom("2026-01-01").effectiveTo("2026-12-31")
                .build();
    }

    private static PackageReview confirmedReview() {
        ParsedPackage pkg = new ParsedPackage(PV, "json", "region",
                List.of(entry("E01"), entry("E02")));
        PackageReview r = new PackageReview(pkg);
        r.confirm("E01", "张主任", "2026-11-12T09:00:00Z");
        r.confirm("E02", "李专员", "2026-11-12T10:30:00Z");
        r.transitionTo(PackageStatus.REVIEWED);
        return r;
    }

    @Test
    void 合并写端点契约_幂等键与请求体字段() {
        RecordingTransport t = new RecordingTransport();
        MergeLedger ledger = new MergeLedger(temp);
        PackageMerger merger = new PackageMerger(t, BASE, ledger, "qc-token");

        PackageReview r = confirmedReview();
        MergeReceipt receipt = merger.merge(r, PV, TV);

        assertEquals(1, t.requests.size(), "只发出一次合并请求");
        OutboundRequest req = t.requests.get(0);
        assertEquals("POST", req.getMethod());
        assertTrue(req.getUrl().contains("/api/v1/indicator-packages/" + PV + "/merge"),
                "合并端点路径须符合契约");
        assertEquals("Bearer qc-token", req.getHeaders().get("Authorization"));
        String key = Hashes.sha256Of(PV, TV);
        assertEquals(key, req.getHeaders().get("Idempotency-Key"), "幂等键 = sha256Of(包版本, 目标版本)");

        JsonNode body = JsonSupport.parse(req.getBody());
        assertEquals(PV, body.path("package_version").asText());
        assertEquals(TV, body.path("target_version").asText(), "请求体含合并后版本号");
        assertEquals(2, body.path("confirmed_entries").size(), "请求体含已确认条目");
        assertEquals("张主任", body.path("confirmers").path("E01").asText(), "请求体含逐条确认人");
        assertNotNull(receipt);
        assertEquals(1, ledger.mergedRecordCount(), "合并后只产生一条已合并记录");
        assertEquals(PackageStatus.MERGED, r.getStatus(), "合并成功后口径包状态推进到 MERGED");
    }

    @Test
    void 重复导入不产生第二条合并记录并提示已合并版本() {
        RecordingTransport t = new RecordingTransport();
        MergeLedger ledger = new MergeLedger(temp);
        PackageMerger merger = new PackageMerger(t, BASE, ledger, "qc-token");
        PackageReview r = confirmedReview();
        merger.merge(r, PV, TV);

        AppException ex = assertThrows(AppException.class, () -> merger.merge(r, PV, TV));

        assertEquals(ErrorCodes.E_PKG_003, ex.getCode());
        assertTrue(ex.getMessage().contains("该包已于") && ex.getMessage().contains("合并至版本 " + TV),
                "须提示「该包已于 X 合并至版本 Y」");
        assertEquals(1, ledger.mergedRecordCount(), "重复导入不产生第二条合并记录");
        assertEquals(1, t.requests.size(), "重复导入不再发出出站请求");
        assertTrue(ledger.hasMerged(PV, TV), "已合并记录这道保险命中");
        assertTrue(ledger.hasIdempotencyKey(Hashes.sha256Of(PV, TV)), "幂等键这道保险也命中（双保险）");
    }

    @Test
    void 回执落库可按包版本与幂等键反查() {
        RecordingTransport t = new RecordingTransport();
        MergeLedger ledger = new MergeLedger(temp);
        PackageMerger merger = new PackageMerger(t, BASE, ledger, "qc-token");
        merger.merge(confirmedReview(), PV, TV);

        MergeReceipt byPkg = ledger.findReceipt(PV);
        assertNotNull(byPkg, "回执可按包版本反查");
        assertFalse(byPkg.getMergedAt().isBlank(), "回执含时间戳");
        assertEquals(2, byPkg.getChangedEntries().size(), "回执含变更条目清单");
        assertEquals("张主任", byPkg.getConfirmers().get("E01"), "回执含逐条确认人");
        assertTrue(byPkg.isChanged(), "回执含是否真有变更");
        assertEquals("merge-001", byPkg.getEndpointReturnId(), "端点返回标识留回执");

        String key = Hashes.sha256Of(PV, TV);
        assertEquals(key, byPkg.getIdempotencyKey());
        MergeReceipt byKey = ledger.findByIdempotencyKey(key);
        assertNotNull(byKey, "回执可按幂等键反查");
        assertEquals(PV, byKey.getPackageVersion());
        assertEquals(TV, byKey.getTargetVersion());
    }

    @Test
    void 合并结果本地落库为DRAFT并记来源包版本() {
        RecordingTransport t = new RecordingTransport();
        MergeLedger ledger = new MergeLedger(temp);
        PackageMerger merger = new PackageMerger(t, BASE, ledger, "qc-token");

        PackageReview r = confirmedReview();
        merger.merge(r, PV, TV);

        JsonNode d = ledger.findLocalDefinition("HAI-E01", TV);
        assertNotNull(d, "合并后本院口径新增对应版本");
        assertEquals("DRAFT", d.path("status").asText(), "按院内规则状态 DRAFT 待复核");
        assertEquals(PV, d.path("source_package_version").asText(), "须记来源包版本");
        assertNotNull(ledger.findLocalDefinition("HAI-E02", TV));
        assertEquals(2, ledger.localDefinitions().size());
        assertEquals(PackageStatus.MERGED, r.getStatus(), "合并成功后口径包状态 MERGED");
    }

    @Test
    void 已登记幂等键即拒绝_不再发出请求() {
        RecordingTransport t = new RecordingTransport();
        MergeLedger ledger = new MergeLedger(temp);
        MergeReceipt pre = MergeReceipt.builder()
                .packageVersion(PV).targetVersion(TV)
                .mergedAt("2026-11-01T00:00:00Z")
                .idempotencyKey(Hashes.sha256Of(PV, TV))
                .changed(true).build();
        ledger.record(pre); // 预置一条历史已合并记录

        PackageMerger merger = new PackageMerger(t, BASE, ledger, "qc-token");
        AppException ex = assertThrows(AppException.class,
                () -> merger.merge(confirmedReview(), PV, TV));

        assertEquals(ErrorCodes.E_PKG_003, ex.getCode());
        assertTrue(ex.getMessage().contains("2026-11-01"), "提示须含已合并时间 X");
        assertEquals(0, t.requests.size(), "命中幂等键保护时根本不发请求");
    }
}
