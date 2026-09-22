package org.example.simple.rpc.common;

/**
 * 与编码格式无关的响应信封。
 *
 * @param success 调用是否成功
 * @param result 成功时的返回值载荷；失败时为 {@code null}
 * @param error 失败时的结构化错误；成功时为 {@code null}
 */
public record RpcResponse(boolean success, RpcPayload result, RpcError error) {
    /**
     * 校验成功标记与结果、错误三者一致：成功必须且只能带结果，失败必须且只能带错误。
     *
     * @throws IllegalArgumentException 当状态与结果不一致时抛出
     */
    public RpcResponse {
        if (success ? result == null || error != null : result != null || error == null) {
            throw new IllegalArgumentException("响应状态与结果不一致");
        }
    }

    /**
     * 构造成功响应。
     *
     * @param result 返回值载荷，返回 void 或 null 时为空值载荷
     * @return 成功响应信封
     */
    public static RpcResponse success(RpcPayload result) {
        return new RpcResponse(true, result, null);
    }

    /**
     * 构造失败响应。
     *
     * @param code 错误类型
     * @param message 面向调用方的中文错误说明
     * @return 失败响应信封
     */
    public static RpcResponse failure(RpcErrorCode code, String message) {
        return new RpcResponse(false, null, new RpcError(code, message));
    }
}
