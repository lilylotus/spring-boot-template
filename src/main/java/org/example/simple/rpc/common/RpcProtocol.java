package org.example.simple.rpc.common;

/**
 * RPC 线协议的共享默认配置。
 */
public final class RpcProtocol {

    /** 默认最大消息体为 8 MiB。 */
    public static final int DEFAULT_MAX_MESSAGE_LENGTH = 8 * 1024 * 1024;

    private RpcProtocol() {
    }
}
