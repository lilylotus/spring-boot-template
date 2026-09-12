package com.example.template.common;

import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：统一把 Controller/Service 抛出的异常转换为 {@link RestResult} 失败响应，
 * Controller 本身不写 try-catch。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：Service 层主动抛出的、可直接展示给调用方的错误信息。
     */
    @ExceptionHandler(BusinessException.class)
    public RestResult<String> handleBusinessException(BusinessException e) {
        return RestResult.failure(e.getMessage());
    }

    /**
     * {@code @Valid} 触发的 Bean Validation 校验失败。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public RestResult<String> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return RestResult.failure(message);
    }

    /**
     * 兜底：其余未预期的异常，避免向调用方暴露内部堆栈信息。
     */
    @ExceptionHandler(Exception.class)
    public RestResult<String> handleException(Exception e) {
        return RestResult.failure("系统异常：" + e.getMessage());
    }
}
