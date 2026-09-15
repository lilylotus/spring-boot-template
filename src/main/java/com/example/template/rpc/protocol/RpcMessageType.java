package com.example.template.rpc.protocol;

import com.example.template.rpc.RpcException;

/**
 * 协议帧中的消息类型字段，对应设计文档里的 REQUEST/RESPONSE/HEARTBEAT 三种取值。
 */
public enum RpcMessageType {

    /** 客户端发起的调用请求。 */
    REQUEST((byte) 1),

    /** 服务端返回的调用结果。 */
    RESPONSE((byte) 2),

    /** 客户端定期发送的心跳包，不携带业务消息体。 */
    HEARTBEAT((byte) 3);

    /** 该类型在协议帧里对应的单字节编码。 */
    private final byte code;

    RpcMessageType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    /**
     * 按协议帧里读到的字节码反查枚举值，用于解码阶段还原消息类型。
     *
     * @param code 协议帧中的消息类型字节
     * @return 对应的枚举值
     */
    public static RpcMessageType fromCode(byte code) {
        for (RpcMessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new RpcException("未知的RPC消息类型编码: " + code);
    }

}
