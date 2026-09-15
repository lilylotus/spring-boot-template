package org.example.simple.rpc.common;

/** 与编码格式无关的响应信封。 */
public record RpcResponse(boolean success, RpcPayload result, RpcError error) {
    public RpcResponse {
        if (success ? result == null || error != null : result != null || error == null) {
            throw new IllegalArgumentException("响应状态与结果不一致");
        }
    }

    public static RpcResponse success(RpcPayload result) {
        return new RpcResponse(true, result, null);
    }

    public static RpcResponse failure(RpcErrorCode code, String message) {
        return new RpcResponse(false, null, new RpcError(code, message));
    }
}
