package com.example.template.rpc.protocol;

/**
 * 一次 RPC 调用响应的消息体：成功时携带方法返回值，失败时携带错误信息。
 */
public class RpcResponse {

    /** 对应的请求 requestId，客户端据此完成请求-响应匹配。 */
    private long requestId;

    /** 本次调用是否成功执行。 */
    private boolean success;

    /** 成功时的方法返回值；失败时为 null。 */
    private Object result;

    /** 失败时的错误描述；成功时为 null。 */
    private String errorMessage;

    public RpcResponse() {
    }

    private RpcResponse(long requestId, boolean success, Object result, String errorMessage) {
        this.requestId = requestId;
        this.success = success;
        this.result = result;
        this.errorMessage = errorMessage;
    }

    /**
     * 构造一个成功的响应。
     *
     * @param requestId 对应请求的 requestId
     * @param result    方法返回值
     * @return 成功响应
     */
    public static RpcResponse success(long requestId, Object result) {
        return new RpcResponse(requestId, true, result, null);
    }

    /**
     * 构造一个失败的响应。
     *
     * @param requestId    对应请求的 requestId
     * @param errorMessage 失败原因
     * @return 失败响应
     */
    public static RpcResponse fail(long requestId, String errorMessage) {
        return new RpcResponse(requestId, false, null, errorMessage);
    }

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @Override
    public String toString() {
        return "RpcResponse{"
            + "requestId=" + requestId
            + ", success=" + success
            + ", errorMessage='" + errorMessage + '\''
            + '}';
    }

}
