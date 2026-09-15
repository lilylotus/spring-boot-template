package com.example.template.rpc.client;

import java.util.concurrent.CompletableFuture;

/**
 * 真正的异步调用入口：直接返回 {@link CompletableFuture}，不在内部阻塞等待结果，业务代码可以
 * 自行选择 {@code .get()} 阻塞、还是 {@code .thenAccept()}/{@code .thenApply()} 等非阻塞回调处理。
 * 与 {@link RpcClientProxy} 提供的"看起来像本地方法调用"的同步接口互补，用于需要真正并发发起
 * 多个请求、不逐个阻塞等待的场景。
 */
public interface AsyncRpcCall {

    /**
     * 发起一次异步调用。
     *
     * @param interfaceName  目标服务接口全限定名
     * @param methodName     目标方法名
     * @param parameterTypes 方法参数类型全限定名列表
     * @param parameters     方法参数值
     * @return 立即返回的 Future，服务端响应到达后异步完成
     */
    CompletableFuture<Object> callAsync(
        String interfaceName, String methodName, String[] parameterTypes, Object[] parameters);

}
