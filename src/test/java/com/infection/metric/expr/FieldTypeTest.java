package com.infection.metric.expr;

import com.infection.metric.common.AppException;
import com.infection.metric.common.ErrorCodes;
import com.infection.metric.common.FieldDef;
import com.infection.metric.common.FieldDictionary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 任务31：字段字典驱动的类型推断、字段缺失拦截、字典版本快照复核。 */
class FieldTypeTest {

    private static FieldDictionary dict(String version) {
        return new FieldDictionary(version, List.of(
                new FieldDef("age", "number", "encounter", true, "EMR"),
                new FieldDef("dept_code", "string", "encounter", true, "EMR"),
                new FieldDef("mdro_flag", "boolean", "infection", false, "HAI")));
    }

    @Test
    void 按source加entity取字段类型() {
        FieldTypeChecker c = new FieldTypeChecker(dict("v1"), "EMR");
        assertEquals("number", c.typeOf("EMR", "encounter", "age"));
        assertEquals("string", c.typeOf("EMR", "encounter", "dept_code"));
        assertNull(c.typeOf("EMR", "encounter", "mdro_flag"), "entity/source 不匹配返回 null");
        assertEquals("boolean", c.typeOf("HAI", "infection", "mdro_flag"));
        assertNotNull(c.fieldDef("EMR", "encounter", "age"));
    }

    @Test
    void 比较符与字典类型不符报E_TYPE_002并说明期望与实际() {
        FieldTypeChecker c = new FieldTypeChecker(dict("v1"), "EMR");
        AppException ex = assertThrows(AppException.class,
                () -> c.check(ConditionTree.leaf("age", "contains", "发热"), "M001"));
        assertEquals(ErrorCodes.E_TYPE_002, ex.getCode());
        assertTrue(ex.render().contains("age"));
        assertTrue(ex.render().contains("number"));
        assertTrue(ex.getExpected().contains("number"), "期望类型含字典类型");
        assertTrue(ex.getActual().contains("contains"), "实际比较符可见");
    }

    @Test
    void 值类型与字段声明不符报E_TYPE_003() {
        FieldTypeChecker c = new FieldTypeChecker(dict("v1"), "EMR");
        AppException ex = assertThrows(AppException.class,
                () -> c.check(ConditionTree.leaf("age", "eq", "不是数值"), "M001"));
        assertEquals(ErrorCodes.E_TYPE_003, ex.getCode());
        assertTrue(ex.render().contains("age"));
        assertTrue(ex.render().contains("数值"));
    }

    @Test
    void 字段缺失拦截文案带口径编码字段名与数据源() {
        FieldTypeChecker c = new FieldTypeChecker(dict("v1"), "EMR");
        AppException ex = assertThrows(AppException.class,
                () -> c.check(ConditionTree.leaf("unknown_field", "eq", "1"), "M001"));
        assertEquals(ErrorCodes.E_FIELD_001, ex.getCode());
        assertTrue(ex.render().contains("M001"));
        assertTrue(ex.render().contains("unknown_field"));
        assertTrue(ex.render().contains("EMR"));
        assertTrue(ex.render().contains("未提供该字段"));
    }

    @Test
    void 字典版本变化提示该口径需复核() {
        FieldTypeChecker c = new FieldTypeChecker(dict("v3"), "EMR");
        assertEquals("v3", c.dictionaryVersion());
        assertFalse(c.needsReview("v3"), "版本一致无需复核");
        assertTrue(c.needsReview("v2"), "版本变化需复核");
        assertTrue(c.needsReview(null), "未记录版本需复核");
        String msg = c.reviewMessage("M001", "v2");
        assertTrue(msg.contains("M001"));
        assertTrue(msg.contains("复核"));
        assertTrue(msg.contains("v2"));
        assertTrue(msg.contains("v3"));
    }
}
