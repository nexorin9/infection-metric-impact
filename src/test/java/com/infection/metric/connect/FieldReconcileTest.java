package com.infection.metric.connect;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.FieldDef;
import com.infection.metric.common.FieldDictionary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 任务39：三类差异各一例；缺失 / 类型不符拦截发布，多余只提示。 */
class FieldReconcileTest {

    private FieldDictionary dictionary() {
        return new FieldDictionary("v1", List.of(
                new FieldDef("dept_code", "string", "encounter", true, "EMR"),
                new FieldDef("discharge_date", "string", "encounter", true, "EMR"),
                new FieldDef("extra_field", "number", "encounter", false, "EMR")));
    }

    @Test
    void missingFieldIsListedAndBlocksPublish() {
        FieldReconciler r = new FieldReconciler();
        var report = r.reconcile(List.of("dept_code", "not_in_dict"), Map.of(), dictionary());
        assertTrue(report.hasMissing());
        assertEquals(1, report.blocking().size());
        assertFalse(report.isPublishAllowed());
        assertTrue(report.humanLines().stream().anyMatch(s -> s.contains("not_in_dict")));
    }

    @Test
    void typeMismatchIsListedAndBlocksPublish() {
        FieldReconciler r = new FieldReconciler();
        var report = r.reconcile(List.of("dept_code"), Map.of("dept_code", "number"), dictionary());
        assertTrue(report.hasTypeMismatch());
        assertFalse(report.isPublishAllowed());
        assertTrue(report.getDifferences().get(0).message().contains("string"));
    }

    @Test
    void extraFieldOnlyWarnsNeverBlocks() {
        FieldReconciler r = new FieldReconciler();
        var report = r.reconcile(List.of("dept_code"), Map.of(), dictionary());
        assertTrue(report.hasExtra());
        assertFalse(report.getDifferences().stream()
                .filter(d -> d.type() == FieldReconciler.DifferenceType.EXTRA)
                .anyMatch(FieldReconciler.Difference::blocksPublish));
    }

    @Test
    void requirePublishableRejectsMissingAndTypeMismatchWithReasons() {
        FieldReconciler r = new FieldReconciler();
        var bad = r.reconcile(List.of("not_in_dict"), Map.of(), dictionary());
        AppException ex = assertThrows(AppException.class, () -> r.requirePublishable(bad));
        assertEquals(ErrorCodes.E_PUBLISH_001, ex.getCode());
        assertTrue(ex.render().contains("not_in_dict"));
        var good = r.reconcile(List.of("dept_code", "discharge_date", "extra_field"), Map.of(), dictionary());
        assertDoesNotThrow(() -> r.requirePublishable(good));
    }

    @Test
    void reportExportsJsonAndChineseLines() {
        FieldReconciler r = new FieldReconciler();
        var report = r.reconcile(List.of("dept_code", "not_in_dict"), Map.of(), dictionary());
        assertFalse(report.toJson().path("publish_allowed").asBoolean());
        assertTrue(report.toJson().path("differences").isArray());
        assertTrue(report.humanLines().size() >= 2);
    }
}
