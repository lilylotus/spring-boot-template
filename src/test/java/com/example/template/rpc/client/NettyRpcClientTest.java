package com.example.template.rpc.client;

import com.example.template.rpc.discovery.ServiceDiscovery;
import com.example.template.rpc.loadbalancer.RoundRobinLoadBalancer;
import com.example.template.rpc.protocol.RpcRequest;
import com.example.template.rpc.protocol.RpcResponse;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NettyRpcClient} 请求-响应匹配与超时竞态处理的离线单元测试，用 {@link EmbeddedChannel}
 * 模拟连接，不需要真实网络。
 */
class NettyRpcClientTest {

    private NettyRpcClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
    }

    @Test
    void shouldCompleteFutureWhenResponseArrivesBeforeTimeout() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        client = new NettyRpcClient(fixedChannelDiscovery(channel), new RoundRobinLoadBalancer(), 5_000L);

        RpcRequest request = new RpcRequest(1L, "com.example.Demo", "echo", new String[0], new Object[0]);
        CompletableFuture<Object> future = client.sendRequest(request);
        client.handleResponse(RpcResponse.success(1L, "hello"));

        assertEquals("hello", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void shouldDiscardLateResponseArrivingAfterTimeout() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        client = new NettyRpcClient(fixedChannelDiscovery(channel), new RoundRobinLoadBalancer(), 50L);

        RpcRequest request = new RpcRequest(2L, "com.example.Demo", "echo", new String[0], new Object[0]);
        CompletableFuture<Object> future = client.sendRequest(request);

        // 等待超过配置的超时时间，让HashedWheelTimer先一步完成Future
        Thread.sleep(300);
        assertTrue(future.isCompletedExceptionally());
        ExecutionException timeoutFailure = assertThrows(
            ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(timeoutFailure.getCause().getMessage().contains("超时"));

        // 响应姗姗来迟，此时requestId已经被超时任务移除，应该被静默丢弃，不改变Future的结果
        client.handleResponse(RpcResponse.success(2L, "too-late"));
        ExecutionException stillTimeoutFailure = assertThrows(
            ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(stillTimeoutFailure.getCause().getMessage().contains("超时"));
    }

    @Test
    void shouldFailFutureImmediatelyWhenWriteFails() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.close();
        client = new NettyRpcClient(fixedChannelDiscovery(channel), new RoundRobinLoadBalancer(), 5_000L);

        RpcRequest request = new RpcRequest(3L, "com.example.Demo", "echo", new String[0], new Object[0]);
        CompletableFuture<Object> future = client.sendRequest(request);

        assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
    }

    private ServiceDiscovery fixedChannelDiscovery(Channel channel) {
        return new ServiceDiscovery() {
            @Override
            public void start(List<InetSocketAddress> addresses) {
            }

            @Override
            public List<Channel> getChannels() {
                return List.of(channel);
            }

            @Override
            public void onAddressesChanged(List<InetSocketAddress> addresses) {
            }

            @Override
            public void shutdown() {
            }
        };
    }

}
