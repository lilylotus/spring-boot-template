package org.example.simple.http;

/**
 * HTTP 客户端错误类型。
 */
public enum HttpErrorType {

    /** 请求参数或配置不合法。 */
    INVALID_ARGUMENT,

    /** 客户端已经关闭。 */
    CLIENT_CLOSED,

    /** 请求执行超时。 */
    TIMEOUT,

    /** 网络连接、TLS 或响应读取失败。 */
    TRANSPORT,

    /** 服务端返回非成功 HTTP 状态。 */
    HTTP_STATUS,

    /** JSON 序列化或反序列化失败。 */
    JSON_CONVERSION
}
