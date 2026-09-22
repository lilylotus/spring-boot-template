package org.example.simple.rpc.common;

/**
 * 完成拆帧后的原始消息，业务编码在工作线程执行。
 *
 * @param messageType 消息类型，取值见 {@link RpcProtocol#REQUEST}、{@link RpcProtocol#RESPONSE}、
 *     {@link RpcProtocol#PING}、{@link RpcProtocol#PONG}
 * @param serializerId 消息体使用的序列化器标识，心跳帧固定为 0
 * @param requestId 请求编号，响应帧回填请求帧的编号，用于客户端匹配在途调用
 * @param body 尚未反序列化的消息体字节，心跳帧为空数组
 * @param receivedNanos 拆帧完成时的纳秒时间戳，用于统计排队与处理耗时
 */
public record RpcFrame(
        byte messageType, byte serializerId, long requestId, byte[] body, long receivedNanos) {
    /**
     * 以当前时间为接收时间创建消息帧。
     *
     * @param messageType 消息类型
     * @param serializerId 序列化器标识
     * @param requestId 请求编号
     * @param body 消息体字节
     */
    public RpcFrame(byte messageType, byte serializerId, long requestId, byte[] body) {
        this(messageType, serializerId, requestId, body, System.nanoTime());
    }
}
