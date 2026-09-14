package org.example.simple.rpc.common;

/** 版本一的固定头协议常量。 */
public final class RpcProtocol {
    public static final int MAGIC = 0x52504331;
    public static final int VERSION = 1;
    public static final int HEADER_LENGTH = 19;
    public static final int DEFAULT_MAX_MESSAGE_LENGTH = 8 * 1024 * 1024;
    public static final byte REQUEST = 1;
    public static final byte RESPONSE = 2;
    public static final byte PING = 3;
    public static final byte PONG = 4;
    private RpcProtocol() { }
}
