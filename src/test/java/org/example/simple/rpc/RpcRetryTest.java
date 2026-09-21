package org.example.simple.rpc;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.example.simple.rpc.client.CallOptions;
import org.example.simple.rpc.client.RpcClient;
import org.example.simple.rpc.common.MessageSerializer;
import org.example.simple.rpc.common.RpcErrorCode;
import org.example.simple.rpc.common.RpcException;
import org.example.simple.rpc.common.RpcFrame;
import org.example.simple.rpc.common.RpcMessageDecoder;
import org.example.simple.rpc.common.RpcMessageEncoder;
import org.example.simple.rpc.common.RpcPayload;
import org.example.simple.rpc.common.RpcProtocol;
import org.example.simple.rpc.common.RpcResponse;
import org.example.simple.rpc.common.SerializerRegistry;
import org.example.simple.rpc.config.RpcConfig;
import org.example.simple.rpc.transport.RpcExecutors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 有限重试测试。
 * <p>
 * 覆盖默认关闭重试、幂等调用在连接失败后的一次重试、重试总预算耗尽、
 * 取消阻止后续重试，以及业务与过载类别不参与重试。
 */
class RpcRetryTest {

    private static final String SERVICE_NAME = "回声服务";

    private EventLoopGroup group;
    private final List<Channel> servers = new ArrayList<>();

    @BeforeEach
    void startEventLoop() {
        group = new MultiThreadIoEventLoopGroup(
            2, RpcExecutors.factory("test-fake"), NioIoHandler.newFactory());
    }

    @AfterEach
    void stopEventLoop() {
        servers.forEach(server -> server.close().awaitUninterruptibly(1, TimeUnit.SECONDS));
        servers.clear();
        group.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS).awaitUninterruptibly(2, TimeUnit.SECONDS);
    }

    @Test
    void retriesIdempotentCallOntoHealthyInstance() throws Exception {
        AtomicInteger closing = new AtomicInteger();
        Channel breaker = fakeServer((ctx, frame) -> {
            closing.incrementAndGet();
            ctx.close();
        });
        Channel healthy = fakeServer((ctx, frame) -> respond(ctx, frame));

        try (RpcClient client = new RpcClient(config())) {
            client.connect("127.0.0.1", port(breaker));
            client.connect("127.0.0.1", port(healthy));

            for (int attempt = 0; attempt < 20; attempt++) {
                assertEquals("回声", call(client, 5000, 1).get(10, TimeUnit.SECONDS));
            }

            // 至少有一次调用命中了会断开连接的实例，并被重试到健康实例上。
            assertTrue(closing.get() > 0, "未触发任何连接失败，无法验证重试");
        }
    }

    @Test
    void doesNotRetryByDefault() throws Exception {
        Channel breaker = fakeServer((ctx, frame) -> ctx.close());

        try (RpcClient client = new RpcClient(config())) {
            client.connect("127.0.0.1", port(breaker));

            ExecutionException failure = assertThrows(
                ExecutionException.class, () -> call(client, 3000, 0).get(10, TimeUnit.SECONDS));

            assertEquals(
                RpcErrorCode.CONNECTION_CLOSED, ((RpcException) failure.getCause()).getErrorCode());
        }
    }

    @Test
    void stopsRetryingWhenTotalBudgetIsExhausted() throws Exception {
        Channel breaker = fakeServer((ctx, frame) -> ctx.close());

        try (RpcClient client = new RpcClient(config())) {
            client.connect("127.0.0.1", port(breaker));

            ExecutionException failure = assertThrows(
                ExecutionException.class, () -> call(client, 10, 1).get(10, TimeUnit.SECONDS));

            assertEquals(RpcErrorCode.TIMEOUT, ((RpcException) failure.getCause()).getErrorCode());
        }
    }

    @Test
    void cancellationPreventsFurtherRetries() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        Channel breaker = fakeServer((ctx, frame) -> {
            requests.incrementAndGet();
            ctx.close();
        });

        try (RpcClient client = new RpcClient(config())) {
            client.connect("127.0.0.1", port(breaker));

            CompletableFuture<String> call = call(client, 5000, 1);
            call.cancel(false);
            Thread.sleep(500);

            assertTrue(call.isDone());
            assertTrue(requests.get() <= 1, "取消后仍然发出了新的重试：" + requests.get());
        }
    }

    @Test
    void doesNotRetryOverloadCategory() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        Channel busy = fakeServer((ctx, frame) -> {
            requests.incrementAndGet();
            MessageSerializer serializer = SerializerRegistry.defaults().get(frame.serializerId());
            byte[] body = serializer.serialize(
                RpcResponse.failure(RpcErrorCode.SERVER_BUSY, "服务端繁忙"));
            ctx.writeAndFlush(
                new RpcFrame(RpcProtocol.RESPONSE, frame.serializerId(), frame.requestId(), body));
        });

        try (RpcClient client = new RpcClient(config())) {
            client.connect("127.0.0.1", port(busy));

            ExecutionException failure = assertThrows(
                ExecutionException.class, () -> call(client, 5000, 1).get(10, TimeUnit.SECONDS));

            assertEquals(
                RpcErrorCode.SERVER_BUSY, ((RpcException) failure.getCause()).getErrorCode());
            Thread.sleep(300);
            assertEquals(1, requests.get(), "过载类别不应触发重试");
        }
    }

    private static RpcConfig config() {
        return RpcConfig.builder()
            .requestTimeoutMillis(5000)
            .connectTimeoutMillis(300)
            .drainTimeoutMillis(500)
            .shutdownTimeoutMillis(1000)
            .build();
    }

    private static CompletableFuture<String> call(RpcClient client, long timeoutMillis, int retries) {
        return client.invokeAsync(
            SERVICE_NAME,
            "echo",
            String.class,
            new Type[0],
            new Object[0],
            new CallOptions((byte) 1, timeoutMillis, null, retries > 0, retries));
    }

    private static void respond(ChannelHandlerContext ctx, RpcFrame frame) {
        MessageSerializer serializer = SerializerRegistry.defaults().get(frame.serializerId());
        byte[] body = serializer.serialize(
            RpcResponse.success(RpcPayload.of("回声", String.class, serializer)));
        ctx.writeAndFlush(
            new RpcFrame(RpcProtocol.RESPONSE, frame.serializerId(), frame.requestId(), body));
    }

    private static int port(Channel server) {
        return ((InetSocketAddress) server.localAddress()).getPort();
    }

    private Channel fakeServer(BiConsumer<ChannelHandlerContext, RpcFrame> onRequest)
        throws InterruptedException {
        Channel server = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel socket) {
                    socket.pipeline()
                        .addLast(new RpcMessageDecoder(65536, SerializerRegistry.defaults()))
                        .addLast(new RpcMessageEncoder(65536))
                        .addLast(new SimpleChannelInboundHandler<RpcFrame>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, RpcFrame frame) {
                                if (frame.messageType() == RpcProtocol.REQUEST) {
                                    onRequest.accept(ctx, frame);
                                }
                            }
                        });
                }
            })
            .bind("127.0.0.1", 0)
            .sync()
            .channel();
        servers.add(server);
        return server;
    }
}
