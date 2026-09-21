package org.example.simple.rpc.client;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.bootstrap.ServerBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import org.example.simple.rpc.server.RpcServer;
import org.example.simple.rpc.server.ServiceRegistry;
import org.example.simple.rpc.transport.RpcExecutors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端请求生命周期竞态测试。
 * <p>
 * 使用可控的假服务端覆盖跨连接伪造响应、重复响应、超时竞争、取消、发送失败、
 * 停机终结以及请求编号耗尽等终态路径，确保每次调用至多完成一次且资源不泄漏。
 */
class RpcClientLifecycleTest {

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
    void ignoresForgedResponseFromAnotherChannelAndClosesIt() throws Exception {
        Channel silent = fakeServer((ctx, frame) -> { }, ctx -> { });
        CompletableFuture<Channel> forgedConnection = new CompletableFuture<>();
        // 该服务端在建立连接后立刻伪造一个属于另一条连接的响应。
        Channel forger = fakeServer((ctx, frame) -> { }, ctx -> {
            forgedConnection.complete(ctx.channel());
            respond(ctx, (byte) 1, 1L);
        });

        try (RpcClient client = new RpcClient(shortConfig())) {
            client.connect("127.0.0.1", port(silent));
            CompletableFuture<String> pending = call(client, 5000);
            Thread.sleep(200);

            client.connect("127.0.0.1", port(forger));

            Channel forged = forgedConnection.get(5, TimeUnit.SECONDS);
            assertTrue(forged.closeFuture().await(5, TimeUnit.SECONDS));
            assertFalse(pending.isDone());
            assertEquals(1, client.pendingCount());
        }
    }

    @Test
    void completesOnceWhenServerRepeatsResponse() throws Exception {
        Channel duplicating = fakeServer(
            (ctx, frame) -> {
                respond(ctx, frame.serializerId(), frame.requestId());
                respond(ctx, frame.serializerId(), frame.requestId());
            },
            ctx -> { });

        try (RpcClient client = new RpcClient(shortConfig())) {
            client.connect("127.0.0.1", port(duplicating));

            assertEquals("回声", call(client, 5000).get(5, TimeUnit.SECONDS));

            Thread.sleep(200);
            assertEquals(0, client.pendingCount());
            // 重复响应属于未知请求编号，只归还预算，不能关闭正常连接。
            assertEquals("回声", call(client, 5000).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void keepsSingleTerminalStateWhenTimeoutRacesResponse() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .requestTimeoutMillis(1000)
            .drainTimeoutMillis(1000)
            .shutdownTimeoutMillis(1000)
            .build();
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, EchoService.class, (EchoService) () -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "回声";
        });

        try (RpcServer server = new RpcServer(registry, config);
             RpcClient client = new RpcClient(config)) {
            server.start("127.0.0.1", 0);
            client.connect("127.0.0.1", server.getPort());

            for (int attempt = 0; attempt < 40; attempt++) {
                CompletableFuture<String> call = call(client, 30);
                try {
                    assertEquals("回声", call.get(5, TimeUnit.SECONDS));
                } catch (ExecutionException expected) {
                    assertInstanceOf(RpcException.class, expected.getCause());
                }
                assertTrue(call.isDone());
            }

            Thread.sleep(300);
            assertEquals(0, client.pendingCount());
        }
    }

    @Test
    void cancellationReleasesPendingSlot() throws Exception {
        Channel silent = fakeServer((ctx, frame) -> { }, ctx -> { });
        Channel responder = fakeServer(
            (ctx, frame) -> respond(ctx, frame.serializerId(), frame.requestId()), ctx -> { });

        try (RpcClient cancelling = new RpcClient(shortConfig());
             RpcClient working = new RpcClient(shortConfig())) {
            cancelling.connect("127.0.0.1", port(silent));
            CompletableFuture<String> call = call(cancelling, 5000);
            Thread.sleep(200);
            assertEquals(1, cancelling.pendingCount());

            call.cancel(false);

            assertThrows(CancellationException.class, call::join);
            Thread.sleep(200);
            assertEquals(0, cancelling.pendingCount());

            working.connect("127.0.0.1", port(responder));
            assertEquals("回声", call(working, 5000).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failsCallWhenRequestExceedsMessageLimit() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .maxMessageLength(1024)
            .requestTimeoutMillis(3000)
            .drainTimeoutMillis(1000)
            .shutdownTimeoutMillis(1000)
            .build();
        Channel silent = fakeServer((ctx, frame) -> { }, ctx -> { });

        try (RpcClient client = new RpcClient(config)) {
            client.connect("127.0.0.1", port(silent));

            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> client.<String>invokeAsync(
                    SERVICE_NAME,
                    "echo",
                    String.class,
                    new Type[] {String.class},
                    new Object[] {"大".repeat(5000)},
                    new CallOptions((byte) 1, 3000, null, false, 0)).get(5, TimeUnit.SECONDS));

            assertEquals(
                RpcErrorCode.SERIALIZATION_FAILED,
                ((RpcException) failure.getCause()).getErrorCode());
            Thread.sleep(200);
            assertEquals(0, client.pendingCount());
        }
    }

    @Test
    void terminatesPendingCallsOnShutdown() throws Exception {
        Channel silent = fakeServer((ctx, frame) -> { }, ctx -> { });
        RpcClient client = new RpcClient(shortConfig());
        client.connect("127.0.0.1", port(silent));
        CompletableFuture<String> pending = call(client, 10000);
        Thread.sleep(200);

        client.close();

        ExecutionException failure =
            assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
        assertInstanceOf(RpcException.class, failure.getCause());
        assertEquals(0, client.pendingCount());
    }

    @Test
    void rejectsNewCallsWhenRequestIdsAreExhausted() throws Exception {
        Channel silent = fakeServer((ctx, frame) -> { }, ctx -> { });

        try (RpcClient client = new RpcClient(shortConfig())) {
            client.connect("127.0.0.1", port(silent));
            // 编号只能前进到 Long.MAX_VALUE，耗尽后必须拒绝新调用而不是回绕复用。
            Field ids = RpcClient.class.getDeclaredField("ids");
            ids.setAccessible(true);
            ((AtomicLong) ids.get(client)).set(Long.MAX_VALUE - 1);

            IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> call(client, 3000));

            assertTrue(error.getMessage().contains("请求编号已耗尽"));
        }
    }

    private static RpcConfig shortConfig() {
        return RpcConfig.builder()
            .requestTimeoutMillis(10000)
            .drainTimeoutMillis(500)
            .shutdownTimeoutMillis(1000)
            .build();
    }

    private static CompletableFuture<String> call(RpcClient client, long timeoutMillis) {
        return client.invokeAsync(
            SERVICE_NAME,
            "echo",
            String.class,
            new Type[0],
            new Object[0],
            new CallOptions((byte) 1, timeoutMillis, null, false, 0));
    }

    private static void respond(ChannelHandlerContext ctx, byte serializerId, long requestId) {
        MessageSerializer serializer = SerializerRegistry.defaults().get(serializerId);
        byte[] body = serializer.serialize(
            RpcResponse.success(RpcPayload.of("回声", String.class, serializer)));
        ctx.writeAndFlush(new RpcFrame(RpcProtocol.RESPONSE, serializerId, requestId, body));
    }

    private static int port(Channel server) {
        return ((InetSocketAddress) server.localAddress()).getPort();
    }

    private Channel fakeServer(
        BiConsumer<ChannelHandlerContext, RpcFrame> onRequest,
        Consumer<ChannelHandlerContext> onActive) throws InterruptedException {
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
                            public void channelActive(ChannelHandlerContext ctx) {
                                onActive.accept(ctx);
                            }

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

    /**
     * 测试用回声服务。
     */
    interface EchoService {

        /** 返回固定回声内容。 */
        String echo();
    }
}
