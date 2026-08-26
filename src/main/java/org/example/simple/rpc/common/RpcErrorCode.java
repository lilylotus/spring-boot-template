package org.example.simple.rpc.common;

/**
 * RPC 调用失败时可安全传输给客户端的错误类型。
 */
public enum RpcErrorCode {
    /** 请求字段或参数不合法。 */
    INVALID_REQUEST,
    /** 指定服务尚未注册。 */
    SERVICE_NOT_FOUND,
    /** 指定方法或参数签名不存在。 */
    METHOD_NOT_FOUND,
    /** 服务方法执行失败。 */
    INVOCATION_FAILED,
    /** 服务端业务执行器已饱和。 */
    SERVER_BUSY,
    /** 客户端连接已关闭或不可用。 */
    CONNECTION_CLOSED,
    /** 客户端等待响应超时。 */
    TIMEOUT,
    /** JSON 序列化或类型转换失败。 */
    SERIALIZATION_FAILED
}
