package org.example.simple.rpc.common;

import java.util.Objects;

/**
 * RPC 响应消息。
 *
 * @param requestId 对应请求的唯一标识
 * @param success 是否调用成功
 * @param result 成功调用的 JSON 字符串结果
 * @param error 失败调用的结构化错误
 */
public record RpcResponse(String requestId, boolean success, String result, RpcError error) {

    public RpcResponse {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("请求标识不能为空");
        }
        if (success && error != null) {
            throw new IllegalArgumentException("成功响应不能包含错误");
        }
        if (success && result == null) {
            throw new IllegalArgumentException("成功响应必须包含 JSON 字符串结果");
        }
        if (!success && error == null) {
            throw new IllegalArgumentException("失败响应必须包含错误");
        }
        if (!success && result != null) {
            throw new IllegalArgumentException("失败响应不能包含结果");
        }
    }

    /**
     * 创建成功响应。
     *
     * @param requestId 请求标识
     * @param result Jackson 序列化后的 JSON 字符串
     * @return 成功响应
     */
    public static RpcResponse success(String requestId, String result) {
        return new RpcResponse(requestId, true, Objects.requireNonNull(result, "JSON 字符串结果不能为空"), null);
    }

    /**
     * 创建失败响应。
     *
     * @param requestId 请求标识
     * @param code 错误类型
     * @param message 错误说明
     * @return 失败响应
     */
    public static RpcResponse failure(String requestId, RpcErrorCode code, String message) {
        return new RpcResponse(requestId, false, null, new RpcError(code, message));
    }
}
