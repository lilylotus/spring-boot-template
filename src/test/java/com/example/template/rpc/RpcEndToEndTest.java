package com.example.template.rpc;

import com.example.template.rpc.client.NettyRpcClient;
import com.example.template.rpc.client.RpcClientProxy;
import com.example.template.rpc.example.EchoRpcService;
import com.example.template.rpc.example.EchoRpcServiceImpl;
import com.example.template.rpc.server.RpcServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 端到端集成测试：真实启动 {@link RpcServer}，通过 {@link RpcClientProxy}/{@link NettyRpcClient}
 * 发起同步、异步、超时三类调用，验证客户端-服务端整条链路(协议编解码+负载均衡+超时机制)可用。
 */
class RpcEndToEndTest {

    private RpcServer server;
    private NettyRpcClient client;

    @BeforeEach
    void startServerAndClient() throws InterruptedException {
        server = new RpcServer(0, 2);
        server.registerService(EchoRpcService.class, new EchoRpcServiceImpl());
        server.start();

        client = new NettyRpcClient(2, 1_000L, 30L);
        client.start(List.of(new InetSocketAddress("127.0.0.1", server.boundPort())));
    }

    @AfterEach
    void stopServerAndClient() throws InterruptedException {
        client.shutdown();
        server.shutdownGracefully();
    }

    @Test
    void shouldCompleteSynchronousCallThroughDynamicProxy() {
        EchoRpcService proxy = new RpcClientProxy(client, 1_000L).getProxy(EchoRpcService.class);
        assertEquals("hello", proxy.echo("hello"));
    }

    @Test
    void shouldCompleteAsynchronousCallWithoutBlocking() throws Exception {
        CompletableFuture<Object> future = client.callAsync(
            EchoRpcService.class.getName(), "echo", new String[]{"java.lang.String"}, new Object[]{"async-hello"});
        assertEquals("async-hello", future.get(2, TimeUnit.SECONDS));
    }

    @Test
    void shouldThrowTimeoutExceptionWhenServerRespondsTooSlowly() {
        // 客户端侧调用超时设置得比服务端人为延迟短，模拟"服务端迟迟不响应"场景
        EchoRpcService proxy = new RpcClientProxy(client, 100L).getProxy(EchoRpcService.class);
        assertThrows(RpcTimeoutException.class, () -> proxy.slowEcho("hello", 1_000L));
    }

}
