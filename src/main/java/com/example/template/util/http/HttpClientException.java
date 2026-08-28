package com.example.template.util.http;

import java.util.Objects;
import java.util.Optional;

/**
 * HTTP 请求、状态处理或内容转换失败时抛出的统一异常。
 */
public final class HttpClientException extends RuntimeException {

    private final HttpErrorType errorType;
    private final HttpResponseData response;

    /**
     * 创建不包含响应的客户端异常。
     *
     * @param errorType 错误类型
     * @param message 中文错误说明
     */
    public HttpClientException(HttpErrorType errorType, String message) {
        this(errorType, message, null, null);
    }

    /**
     * 创建包含底层原因的客户端异常。
     *
     * @param errorType 错误类型
     * @param message 中文错误说明
     * @param cause 底层原因
     */
    public HttpClientException(HttpErrorType errorType, String message, Throwable cause) {
        this(errorType, message, cause, null);
    }

    /**
     * 创建包含 HTTP 响应的客户端异常。
     *
     * @param errorType 错误类型
     * @param message 中文错误说明
     * @param response 原始 HTTP 响应
     */
    public HttpClientException(HttpErrorType errorType, String message, HttpResponseData response) {
        this(errorType, message, null, response);
    }

    private HttpClientException(
        HttpErrorType errorType,
        String message,
        Throwable cause,
        HttpResponseData response) {
        super(Objects.requireNonNull(message, "异常消息不能为空"), cause);
        this.errorType = Objects.requireNonNull(errorType, "错误类型不能为空");
        this.response = response;
    }

    /**
     * 返回错误类型。
     *
     * @return 错误类型
     */
    public HttpErrorType getErrorType() {
        return errorType;
    }

    /**
     * 返回导致状态错误的原始响应。
     *
     * @return 原始响应；非状态错误时为空
     */
    public Optional<HttpResponseData> getResponse() {
        return Optional.ofNullable(response);
    }
}
