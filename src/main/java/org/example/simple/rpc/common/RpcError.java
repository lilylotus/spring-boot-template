package org.example.simple.rpc.common;

import java.util.Objects;

/**
 * RPC 失败响应中的结构化错误。
 *
 * @param code 稳定的错误类型
 * @param message 面向调用方的中文错误说明
 */
public record RpcError(RpcErrorCode code, String message) {

    public RpcError {
        Objects.requireNonNull(code, "错误类型不能为空");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("错误信息不能为空");
        }
    }
}
