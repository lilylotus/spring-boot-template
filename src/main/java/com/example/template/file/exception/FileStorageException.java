package com.example.template.file.exception;

import org.springframework.http.HttpStatus;

/**
 * 文件上传下载流程中可预期的业务异常(如文件不存在、已失效、超出大小限制)，携带对应的HTTP状态码，
 * 由{@code GlobalExceptionHandler}统一转换成{@code RestResult}错误响应；未预期的IO异常等不用
 * 本异常包装，直接交给兜底的未预期异常处理逻辑。
 */
public class FileStorageException extends RuntimeException {

    private final HttpStatus httpStatus;

    public FileStorageException(HttpStatus httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
