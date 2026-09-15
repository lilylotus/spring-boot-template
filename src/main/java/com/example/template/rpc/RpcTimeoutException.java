package com.example.template.rpc;

/**
 * 客户端在配置的超时时间内未收到服务端响应时抛出，区别于其它网络/业务异常，便于调用方针对性处理。
 */
public class RpcTimeoutException extends RpcException {

    public RpcTimeoutException(String message) {
        super(message);
    }

}
