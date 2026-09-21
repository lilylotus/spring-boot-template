package org.example.simple.rpc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.example.simple.rpc.client.RpcClient;
import org.example.simple.rpc.common.RpcFrame;
import org.example.simple.rpc.common.RpcMessageDecoder;
import org.example.simple.rpc.common.RpcMessageEncoder;
import org.example.simple.rpc.common.RpcProtocol;
import org.example.simple.rpc.common.SerializerRegistry;
import org.example.simple.rpc.config.RpcConfig;
import org.example.simple.rpc.server.RpcServer;
import org.example.simple.rpc.server.ServiceRegistry;
import org.example.simple.rpc.transport.RpcExecutors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端心跳与空闲失活测试。
 * <p>
 * 使用不安装框架管线的裸连接直接收发控制帧，覆盖心跳关联校验、读空闲失活、
 * 控制消息的字节预算释放，以及业务线程池饱和时心跳仍能推进。
 */
class RpcHeartbeatTest {

    private static final String SERVICE_NAME = "心跳服务";

    private EventLoopGroup group;

    @BeforeEach
    void startEventLoop() {
        group = new MultiThreadIoEventLoopGroup(
            1, RpcExecutors.factory("test-raw"), NioIoHandler.newFactory());
    }

    @AfterEach
    void stopEventLoop() {
        group.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS).awaitUninterruptibly(2, TimeUnit.SECONDS);
    }

    @Test
    void pingsIdleConnectionAndReleasesControlMessageBudget() throws Exception {
        RpcConfig config = heartbeatConfig(200, 5000);
        try (RpcServer server = new RpcServer(new ServiceRegistry(), config)) {
            server.start("127.0.0.1", 0);
            try (Raw raw = new Raw(server.getPort())) {
                RpcFrame ping = raw.frames.poll(3, TimeUnit.SECONDS);

                assertNotNull(ping);
                assertEquals(RpcProtocol.PING, ping.messageType());
                assertEquals(0, ping.serializerId());
                assertEquals(0, ping.body().length);

                raw.send(new RpcFrame(RpcProtocol.PONG, (byte) 0, ping.requestId(), new byte[0]));
                assertNotNull(raw.frames.poll(3, TimeUnit.SECONDS));
                assertTrue(raw.channel.isActive());
                assertEquals(0, server.bufferedBytes());
            }
        }
    }

    @Test
    void closesConnectionWhenHeartbeatAckDoesNotMatch() throws Exception {
        RpcConfig config = heartbeatConfig(200, 5000);
        try (RpcServer server = new RpcServer(new ServiceRegistry(), config)) {
            server.start("127.0.0.1", 0);
            try (Raw raw = new Raw(server.getPort())) {
                RpcFrame ping = raw.frames.poll(3, TimeUnit.SECONDS);
                assertNotNull(ping);

                raw.send(new RpcFrame(RpcProtocol.PONG, (byte) 0, ping.requestId() + 100, new byte[0]));

                assertTrue(raw.channel.closeFuture().await(3, TimeUnit.SECONDS));
                assertEquals(0, server.bufferedBytes());
            }
        }
    }

    @Test
    void closesConnectionThatStopsRespondingBeforeReadIdleTimeout() throws Exception {
        RpcConfig config = heartbeatConfig(200, 600);
        try (RpcServer server = new RpcServer(new ServiceRegistry(), config)) {
            server.start("127.0.0.1", 0);
            try (Raw raw = new Raw(server.getPort())) {
                // 裸连接不回应任何心跳，读空闲到期后服务端必须主动关闭失活连接。
                assertTrue(raw.channel.closeFuture().await(5, TimeUnit.SECONDS));
                assertEquals(0, server.bufferedBytes());
            }
        }
    }

    @Test
    void heartbeatAdvancesWhileBusinessPoolIsSaturated() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .heartbeatIntervalMillis(200)
            .readIdleTimeoutMillis(5000)
            .businessThreads(1)
            .businessQueueCapacity(1)
            .drainTimeoutMillis(3000)
            .shutdownTimeoutMillis(2000)
            .build();
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, SlowService.class, (SlowService) () -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "完成";
        });

        try (RpcServer server = new RpcServer(registry, config);
             RpcClient client = new RpcClient(config)) {
            server.start("127.0.0.1", 0);
            client.connect("127.0.0.1", server.getPort());

            CompletableFuture<String> first = call(client);
            CompletableFuture<String> second = call(client);

            try (Raw raw = new Raw(server.getPort())) {
                // 业务池被占满期间，心跳走独立控制路径，仍然可以按时发出。
                RpcFrame ping = raw.frames.poll(3, TimeUnit.SECONDS);
                assertNotNull(ping);
                assertEquals(RpcProtocol.PING, ping.messageType());

                raw.send(new RpcFrame(RpcProtocol.PONG, (byte) 0, ping.requestId(), new byte[0]));
                assertTrue(raw.channel.isActive());
            }

            first.join();
            second.join();
        }
    }

    private static CompletableFuture<String> call(RpcClient client) {
        return client.invokeAsync(SERVICE_NAME, "run", String.class, new Class<?>[0], new Object[0]);
    }

    private static RpcConfig heartbeatConfig(int heartbeatMillis, int readIdleMillis) {
        return RpcConfig.builder()
            .heartbeatIntervalMillis(heartbeatMillis)
            .readIdleTimeoutMillis(readIdleMillis)
            .drainTimeoutMillis(2000)
            .shutdownTimeoutMillis(2000)
            .build();
    }

    /**
     * 测试用的慢服务，用于占满业务线程池。
     */
    interface SlowService {

        /** 执行一次耗时调用。 */
        String run();
    }

    /**
     * 不安装框架管线的裸连接，只做帧编解码，便于直接构造控制消息。
     */
    private final class Raw implements AutoCloseable {

        private final LinkedBlockingQueue<RpcFrame> frames = new LinkedBlockingQueue<>();
        private final Channel channel;

        Raw(int port) throws InterruptedException {
            channel = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socket) {
                        socket.pipeline()
                            .addLast(new RpcMessageDecoder(1024, SerializerRegistry.defaults()))
                            .addLast(new RpcMessageEncoder(1024))
                            .addLast(new SimpleChannelInboundHandler<RpcFrame>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, RpcFrame frame) {
                                    frames.add(frame);
                                }
                            });
                    }
                })
                .connect("127.0.0.1", port)
                .sync()
                .channel();
        }

        void send(RpcFrame frame) {
            channel.writeAndFlush(frame);
        }

        @Override
        public void close() {
            channel.close().awaitUninterruptibly(1, TimeUnit.SECONDS);
        }
    }
}
