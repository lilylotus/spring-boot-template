package com.example.template.rpc;

/**
 * 消息体序列化/反序列化失败时抛出，通常意味着协议帧已损坏或收发双方序列化方式不匹配。
 */
public class RpcSerializationException extends RpcException {

    public RpcSerializationException(String message, Throwable cause) {
        super(message, cause);
    }

}
