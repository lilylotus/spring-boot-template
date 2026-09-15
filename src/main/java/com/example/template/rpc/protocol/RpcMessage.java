package com.example.template.rpc.protocol;

/**
 * 协议帧对应的内存模型：requestId + 消息类型 + 序列化方式标识 + 消息体。
 * <p>
 * {@code data} 在消息类型为 {@link RpcMessageType#REQUEST} 时是 {@link RpcRequest}，
 * 为 {@link RpcMessageType#RESPONSE} 时是 {@link RpcResponse}，为
 * {@link RpcMessageType#HEARTBEAT} 时为 {@code null}（心跳不携带业务消息体）。
 */
public class RpcMessage {

    private long requestId;

    private RpcMessageType messageType;

    /** 消息体使用的序列化方式编码，编解码器据此在 {@link RpcSerializerRegistry} 中选择实现。 */
    private byte serializerType;

    private Object data;

    public RpcMessage() {
    }

    public RpcMessage(long requestId, RpcMessageType messageType, byte serializerType, Object data) {
        this.requestId = requestId;
        this.messageType = messageType;
        this.serializerType = serializerType;
        this.data = data;
    }

    /**
     * 构造一条心跳消息，不携带业务消息体。
     *
     * @param requestId     心跳自身的标识（用于日志排查，不参与请求-响应匹配）
     * @param serializerType 序列化方式编码（心跳无消息体，此字段仅为协议头完整性保留）
     * @return 心跳消息
     */
    public static RpcMessage heartbeat(long requestId, byte serializerType) {
        return new RpcMessage(requestId, RpcMessageType.HEARTBEAT, serializerType, null);
    }

    /**
     * 构造一条请求消息。
     *
     * @param serializerType 序列化方式编码
     * @param request        请求消息体
     * @return 请求消息
     */
    public static RpcMessage request(byte serializerType, RpcRequest request) {
        return new RpcMessage(request.getRequestId(), RpcMessageType.REQUEST, serializerType, request);
    }

    /**
     * 构造一条响应消息。
     *
     * @param serializerType 序列化方式编码
     * @param response       响应消息体
     * @return 响应消息
     */
    public static RpcMessage response(byte serializerType, RpcResponse response) {
        return new RpcMessage(response.getRequestId(), RpcMessageType.RESPONSE, serializerType, response);
    }

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    public RpcMessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(RpcMessageType messageType) {
        this.messageType = messageType;
    }

    public byte getSerializerType() {
        return serializerType;
    }

    public void setSerializerType(byte serializerType) {
        this.serializerType = serializerType;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

}
