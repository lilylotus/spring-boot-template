package org.example.simple.rpc;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
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
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.example.simple.rpc.client.CallOptions;
import org.example.simple.rpc.client.RpcClient;
import org.example.simple.rpc.common.JacksonJsonSerializer;
import org.example.simple.rpc.common.MessageSerializer;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 与 Protostuff 双编码测试。
 * <p>
 * 覆盖同一连接上的混合编码并发调用、未注册与被禁用编号的拒绝，
 * 以及响应编号与请求不一致时的连接关闭。
 */
class RpcDualEncodingTest {

    private static final String SERVICE_NAME = "回声服务";

    private EventLoopGroup group;

    @BeforeEach
    void startEventLoop() {
        group = new MultiThreadIoEventLoopGroup(
            2, RpcExecutors.factory("test-raw"), NioIoHandler.newFactory());
    }

    @AfterEach
    void stopEventLoop() {
        group.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS).awaitUninterruptibly(2, TimeUnit.SECONDS);
    }

    @Test
    void mixesBothEncodingsConcurrentlyOnSameConnection() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .requestTimeoutMillis(10000)
            .drainTimeoutMillis(3000)
            .shutdownTimeoutMillis(2000)
            .build();
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, EchoService.class, (EchoService) text -> "回声:" + text);

        try (RpcServer server = new RpcServer(registry, config);
             RpcClient client = new RpcClient(config)) {
            server.start("127.0.0.1", 0);
            client.connect("127.0.0.1", server.getPort());

            ExecutorService callers = Executors.newFixedThreadPool(8);
            try {
                List<Future<String>> results = new ArrayList<>();
                for (int index = 0; index < 200; index++) {
                    byte encoding = (byte) (index % 2 == 0 ? 1 : 2);
                    String argument = "参数" + index;
                    results.add(callers.submit(() -> client.<String>invokeAsync(
                        SERVICE_NAME,
                        "echo",
                        String.class,
                        new Type[] {String.class},
                        new Object[] {argument},
                        new CallOptions(encoding, 10000, null, false, 0)).get()));
                }

                for (int index = 0; index < results.size(); index++) {
                    assertEquals("回声:参数" + index, results.get(index).get(20, TimeUnit.SECONDS));
                }
            } finally {
                callers.shutdownNow();
            }

            assertEquals(0, client.pendingCount());
        }
    }

    @Test
    void rejectsUnregisteredSerializerId() {
        assertThrows(IllegalArgumentException.class, () -> SerializerRegistry.defaults().get((byte) 9));

        RpcConfig config = RpcConfig.builder().drainTimeoutMillis(1000).shutdownTimeoutMillis(1000).build();
        try (RpcClient client = new RpcClient(config)) {
            assertThrows(
                IllegalArgumentException.class,
                () -> client.invokeAsync(
                    SERVICE_NAME,
                    "echo",
                    String.class,
                    new Type[] {String.class},
                    new Object[] {"甲"},
                    new CallOptions((byte) 9, 1000, null, false, 0)));
        }
    }

    @Test
    void closesConnectionWhenFrameUsesDisabledSerializer() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .drainTimeoutMillis(1000)
            .shutdownTimeoutMillis(1000)
            .build();
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, EchoService.class, (EchoService) text -> text);

        // 服务端只启用 JSON，收到 Protostuff 编号的报文必须直接关闭连接。
        try (RpcServer server = new RpcServer(
                registry, config, new SerializerRegistry(new JacksonJsonSerializer()), null)) {
            server.start("127.0.0.1", 0);
            Channel raw = rawClient(server.getPort());

            raw.writeAndFlush(new RpcFrame(RpcProtocol.REQUEST, (byte) 2, 1L, new byte[] {1, 2, 3}));

            assertTrue(raw.closeFuture().await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closesConnectionWhenResponseSerializerDoesNotMatchRequest() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .requestTimeoutMillis(5000)
            .drainTimeoutMillis(1000)
            .shutdownTimeoutMillis(1000)
            .build();
        Channel fakeServer = startMismatchingServer();
        try (RpcClient client = new RpcClient(config)) {
            int port = ((java.net.InetSocketAddress) fakeServer.localAddress()).getPort();
            client.connect("127.0.0.1", port);

            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> client.invokeAsync(
                    SERVICE_NAME,
                    "echo",
                    String.class,
                    new Type[] {String.class},
                    new Object[] {"甲"},
                    new CallOptions((byte) 1, 5000, null, false, 0)).get(10, TimeUnit.SECONDS));

            assertInstanceOf(RpcException.class, failure.getCause());
        } finally {
            fakeServer.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
    }

    /**
     * 启动一个故意使用另一种编号回应的服务端，用于构造响应编号不匹配场景。
     *
     * @return 已经绑定端口的监听 Channel
     * @throws InterruptedException 绑定被中断时抛出
     */
    private Channel startMismatchingServer() throws InterruptedException {
        return new ServerBootstrap()
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
                                if (frame.messageType() != RpcProtocol.REQUEST) {
                                    return;
                                }
                                byte other = (byte) (frame.serializerId() == 1 ? 2 : 1);
                                MessageSerializer serializer = SerializerRegistry.defaults().get(other);
                                byte[] body = serializer.serialize(RpcResponse.success(
                                    RpcPayload.of("回声", String.class, serializer)));
                                ctx.writeAndFlush(
                                    new RpcFrame(RpcProtocol.RESPONSE, other, frame.requestId(), body));
                            }
                        });
                }
            })
            .bind("127.0.0.1", 0)
            .sync()
            .channel();
    }

    private Channel rawClient(int port) throws InterruptedException {
        return new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel socket) {
                    socket.pipeline()
                        .addLast(new RpcMessageDecoder(65536, SerializerRegistry.defaults()))
                        .addLast(new RpcMessageEncoder(65536))
                        .addLast(new SimpleChannelInboundHandler<RpcFrame>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, RpcFrame frame) {
                                /* 该用例只验证服务端的拒绝行为。 */
                            }
                        });
                }
            })
            .connect("127.0.0.1", port)
            .sync()
            .channel();
    }

    /**
     * 测试用回声服务。
     */
    interface EchoService {

        /**
         * 返回带前缀的回声。
         *
         * @param text 原始文本
         * @return 回声文本
         */
        String echo(String text);
    }
}
