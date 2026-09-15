package com.example.template.rpc.client;

import com.example.template.rpc.RpcException;
import com.example.template.rpc.RpcTimeoutException;
import com.example.template.rpc.protocol.RpcRequest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 动态代理把接口调用转成RPC请求，是客户端对业务代码"透明化"的关键：业务代码拿到的是一个普通的
 * 接口实例，调用其方法时实际发生的网络通信对它完全透明。
 */
public class RpcClientProxy implements InvocationHandler {

    private final NettyRpcClient rpcClient;

    /** 单次同步调用最长等待时间；超过后转换为 {@link RpcTimeoutException} 抛给调用方。 */
    private final long callTimeoutMillis;

    public RpcClientProxy(NettyRpcClient rpcClient, long callTimeoutMillis) {
        this.rpcClient = rpcClient;
        this.callTimeoutMillis = callTimeoutMillis;
    }

    /**
     * 为指定接口创建一个动态代理实例，业务代码像调用本地方法一样调用它即可发起RPC请求。
     *
     * @param interfaceClass 目标服务接口
     * @param <T>             接口类型
     * @return 代理实例
     */
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Class<T> interfaceClass) {
        return (T) Proxy.newProxyInstance(
            interfaceClass.getClassLoader(), new Class<?>[]{interfaceClass}, this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        // Object自身的方法(equals/hashCode/toString)不应该被转发成网络请求，直接本地处理
        if (method.getDeclaringClass() == Object.class) {
            return invokeLocalObjectMethod(proxy, method, args);
        }

        RpcRequest request = new RpcRequest(
            rpcClient.nextRequestId(),
            method.getDeclaringClass().getName(),
            method.getName(),
            toParameterTypeNames(method.getParameterTypes()),
            args);

        CompletableFuture<Object> future = rpcClient.sendRequest(request);
        try {
            // 同步调用：阻塞等待Future完成，业务代码看到的是一次普通的方法返回
            return future.get(callTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RpcTimeoutException("请求超时: " + request.getRequestId());
        } catch (ExecutionException e) {
            throw new RpcException("RPC调用失败: " + request, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException("RPC调用被中断: " + request, e);
        }
    }

    private String[] toParameterTypeNames(Class<?>[] parameterTypes) {
        return Arrays.stream(parameterTypes).map(Class::getName).toArray(String[]::new);
    }

    private Object invokeLocalObjectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "toString": {
                return "RpcClientProxy@" + Integer.toHexString(System.identityHashCode(proxy));
            }
            case "hashCode": {
                return System.identityHashCode(proxy);
            }
            case "equals": {
                return proxy == (args != null ? args[0] : null);
            }
            default: {
                throw new RpcException("不支持的方法调用: " + method);
            }
        }
    }

}
