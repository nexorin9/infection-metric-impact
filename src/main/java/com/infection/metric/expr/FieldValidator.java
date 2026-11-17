package com.infection.metric.expr;

import com.infection.metric.common.DataRecord;
import com.infection.metric.common.ErrorCodes;

/**
 * 字段存在性校验与脏数据边界（任务6）。
 *
 * <p>口径声明了但数据源没有的字段 → 显式失败，文案
 * 「口径 {code} 声明依赖字段 {field}，数据源 {source} 未提供该字段」（E_FIELD_001）。
 * 脏数据（超长字段 E_DATA_002、GBK/UTF-8 编码混编 E_DATA_001）显式失败，
 * 绝不静默按 0 计入分母。空值交由 {@link Evaluator} 按口径显式声明的 NullPolicy 处理。</p>
 */
public final class FieldValidator {

    /** 字段取值默认长度上限。 */
    public static final int DEFAULT_MAX_LENGTH = 256;

    private final int maxLength;

    public FieldValidator() {
        this(DEFAULT_MAX_LENGTH);
    }

    public FieldValidator(int maxLength) {
        this.maxLength = maxLength;
    }

    public int getMaxLength() {
        return maxLength;
    }

    /** 完整校验：声明字段存在性 + 脏数据。 */
    public void validate(ConditionTree tree, DataRecord rec, String code, String source) {
        if (rec == null) {
            throw ExpressionErrors.error(ErrorCodes.E_DATA_001,
                    "field", "(记录)", "reason", "记录为空，不得静默计入分母");
        }
        validateDeclaredFields(tree, rec, code, source);
        validateDirty(rec);
    }

    /** 口径声明字段逐个核对数据源是否提供；缺失抛 E_FIELD_001（带 code/field/source）。 */
    public void validateDeclaredFields(ConditionTree tree, DataRecord rec, String code, String source) {
        for (String field : tree.declaredFields()) {
            if (!rec.has(field)) {
                throw ExpressionErrors.error(ErrorCodes.E_FIELD_001,
                        "code", code, "field", field, "source", source);
            }
        }
    }

    /** 脏数据检查：超长字段、编码混编（替换字符/私用区/异常控制字符）显式失败。 */
    public void validateDirty(DataRecord rec) {
        for (String field : rec.fields()) {
            Object v = rec.getOrNull(field);
            if (!(v instanceof String s)) {
                continue;
            }
            if (s.length() > maxLength) {
                throw ExpressionErrors.error(ErrorCodes.E_DATA_002,
                        "field", field,
                        "length", String.valueOf(s.length()),
                        "limit", String.valueOf(maxLength));
            }
            if (looksLikeMojibake(s)) {
                throw ExpressionErrors.error(ErrorCodes.E_DATA_001,
                        "field", field,
                        "reason", "疑似 GBK/UTF-8 编码混编（含替换字符、私用区或异常控制字符）");
            }
        }
    }

    private static boolean looksLikeMojibake(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD') {
                return true; // UTF-8 解码失败产生的替换字符，编码混编最典型特征
            }
            if (Character.getType(c) == Character.PRIVATE_USE) {
                return true;
            }
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                return true;
            }
        }
        return false;
    }
}
