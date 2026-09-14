package com.evops.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    /** 唯一约束等数据库完整性冲突：关键业务键必须唯一。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ApiResponse<Void> handleDataIntegrity(DataIntegrityViolationException ex) {
        return ApiResponse.fail("数据冲突：业务键已存在或存在关联数据，操作被拒绝");
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? "请求参数校验失败" : fieldError.getDefaultMessage();
        return ApiResponse.fail(message);
    }

    /** 文件上传问题：缺少文件部分、超出大小限制等。 */
    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    public ApiResponse<Void> handleMultipart(Exception ex) {
        return ApiResponse.fail("文件上传失败: " + ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        return ApiResponse.fail("系统处理失败");
    }
}
