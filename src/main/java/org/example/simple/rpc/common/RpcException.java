package org.example.simple.rpc.common;

/**
 * RPC 客户端或公共协议处理失败时抛出的异常。
 */
public class RpcException extends RuntimeException {

    private final RpcErrorCode errorCode;

    /**
     * 创建 RPC 异常。
     *
     * @param errorCode 错误类型
     * @param message 中文错误说明
     */
    public RpcException(RpcErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 创建带原因的 RPC 异常。
     *
     * @param errorCode 错误类型
     * @param message 中文错误说明
     * @param cause 原始异常
     */
    public RpcException(RpcErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取稳定错误类型。
     *
     * @return 错误类型
     */
    public RpcErrorCode getErrorCode() {
        return errorCode;
    }
}
