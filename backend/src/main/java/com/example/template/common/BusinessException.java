package com.example.template.common;

/**
 * 业务异常：Service 层校验失败（如唯一性冲突、状态不合法、审批链未配置等）时抛出，
 * 由 {@link GlobalExceptionHandler} 统一捕获并转换为 {@link RestResult} 失败响应。
 * Controller 不捕获该异常，遵循"Controller 不写 try-catch"的规范。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BusinessException(String message) {
        super(message);
    }
}
