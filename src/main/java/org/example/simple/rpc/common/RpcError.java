package org.example.simple.rpc.common;

import java.util.Objects;

/**
 * RPC 失败响应中的结构化错误。
 *
 * @param code 稳定的错误类型
 * @param message 面向调用方的中文错误说明
 */
public record RpcError(RpcErrorCode code, String message) {

    /**
     * 校验错误类型与说明均已填写。
     *
     * @throws NullPointerException 错误类型为空时抛出
     * @throws IllegalArgumentException 错误说明为空或全空白时抛出
     */
    public RpcError {
        Objects.requireNonNull(code, "错误类型不能为空");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("错误信息不能为空");
        }
    }
}
