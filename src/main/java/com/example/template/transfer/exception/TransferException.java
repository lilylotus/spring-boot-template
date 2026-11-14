package com.example.template.transfer.exception;

import org.springframework.http.HttpStatus;

/**
 * 转账流程中可预期的业务异常(转出转入相同、账户不存在、余额不足、重复请求处理中、幂等键冲突)，
 * 携带对应的HTTP状态码，由 {@code GlobalExceptionHandler} 统一转换成 {@code RestResult} 错误响应。
 * Redis故障、锁所有权丢失等未预期问题不使用本异常包装，直接交给兜底的未预期异常处理逻辑返回500。
 */
public class TransferException extends RuntimeException {

    /** 该业务错误对应的HTTP状态码。 */
    private final HttpStatus httpStatus;

    public TransferException(HttpStatus httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
