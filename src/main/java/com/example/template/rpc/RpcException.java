package com.example.template.rpc;

/**
 * RPC 框架统一的运行时异常基类，涵盖协议编解码、序列化、网络调用、服务分发等环节的失败场景。
 */
public class RpcException extends RuntimeException {

    public RpcException(String message) {
        super(message);
    }

    public RpcException(String message, Throwable cause) {
        super(message, cause);
    }

}
