package org.example.simple.rpc.common;

/** 完成拆帧后的原始消息，业务编码在工作线程执行。 */
public record RpcFrame(
        byte messageType, byte serializerId, long requestId, byte[] body, long receivedNanos) {
    public RpcFrame(byte messageType, byte serializerId, long requestId, byte[] body) {
        this(messageType, serializerId, requestId, body, System.nanoTime());
    }
}
