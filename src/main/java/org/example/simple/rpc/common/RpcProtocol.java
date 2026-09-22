package org.example.simple.rpc.common;

/** 版本一的固定头协议常量。 */
public final class RpcProtocol {
    /** 协议魔数，写在每帧首部用于识别非本协议流量。 */
    public static final int MAGIC = 0x52504331;

    /** 协议版本号，解码时版本不匹配即判定为坏帧。 */
    public static final int VERSION = 1;

    /** 固定头长度：魔数 4 + 版本 1 + 类型 1 + 序列化标识 1 + 请求编号 8 + 体长度 4。 */
    public static final int HEADER_LENGTH = 19;

    /** 未显式配置时允许的最大消息体字节数。 */
    public static final int DEFAULT_MAX_MESSAGE_LENGTH = 8 * 1024 * 1024;

    /** 消息类型：请求帧。 */
    public static final byte REQUEST = 1;

    /** 消息类型：响应帧。 */
    public static final byte RESPONSE = 2;

    /** 消息类型：心跳请求帧，无消息体。 */
    public static final byte PING = 3;

    /** 消息类型：心跳响应帧，无消息体。 */
    public static final byte PONG = 4;

    /** 常量类，禁止实例化。 */
    private RpcProtocol() {}
}
