package com.evops.common;

/**
 * 业务规则异常：唯一键冲突、非法状态流转、已落账/已验收记录被删除等。
 * 由 GlobalExceptionHandler 统一转换为 ApiResponse.fail。
 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
