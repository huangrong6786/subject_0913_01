package com.evops.aquaculture.csv;

/**
 * 单行校验失败：携带失败字段名、原始值与失败原因，
 * 由导入服务落账到 t_obs_import_row，逐行隔离不影响其他行。
 */
public class RowValidationException extends RuntimeException {

    private final String fieldName;
    private final String rawValue;

    public RowValidationException(String fieldName, String rawValue, String reason) {
        super(reason);
        this.fieldName = fieldName;
        this.rawValue = rawValue;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getRawValue() {
        return rawValue;
    }
}
