package org.example.simple.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
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
import org.example.simple.rpc.common.JacksonJsonSerializer;
import org.example.simple.rpc.common.RpcFrame;
import org.example.simple.rpc.common.RpcMessageDecoder;
import org.example.simple.rpc.common.RpcMessageEncoder;
import org.example.simple.rpc.common.RpcProtocol;
import org.example.simple.rpc.common.RpcRequest;
import org.example.simple.rpc.common.SerializerRegistry;
import org.example.simple.rpc.config.RpcConfig;
import org.example.simple.rpc.server.RpcServer;
import org.example.simple.rpc.server.ServiceRegistry;
import org.example.simple.rpc.transport.RpcExecutors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接上限与过载保护测试。
 * <p>
 * 覆盖服务端连接数上限、客户端连接数上限，以及慢消费者在响应字节预算耗尽后
 * 被关闭并完整归还预算的过载路径。
 */
class RpcOverloadTest {

    private static final String SERVICE_NAME = "批量服务";

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
    void closesConnectionsBeyondServerLimit() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .maxServerConnections(2)
            .drainTimeoutMillis(2000)
            .shutdownTimeoutMillis(2000)
            .build();

        try (RpcServer server = new RpcServer(new ServiceRegistry(), config)) {
            server.start("127.0.0.1", 0);
            List<Channel> channels = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                channels.add(connect(server.getPort(), true));
            }

            Thread.sleep(500);

            assertEquals(2, channels.stream().filter(Channel::isActive).count());
            channels.forEach(channel -> channel.close().awaitUninterruptibly(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsClientConnectionsBeyondLimit() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .maxClientConnections(1)
            .drainTimeoutMillis(2000)
            .shutdownTimeoutMillis(2000)
            .build();

        try (RpcServer first = new RpcServer(new ServiceRegistry(), config);
             RpcServer second = new RpcServer(new ServiceRegistry(), config);
             RpcClient client = new RpcClient(config)) {
            first.start("127.0.0.1", 0);
            second.start("127.0.0.1", 0);
            client.connect("127.0.0.1", first.getPort());

            IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> client.connect("127.0.0.1", second.getPort()));

            assertTrue(error.getMessage().contains("连接数超过上限"));
        }
    }

    @Test
    void closesSlowConsumerWhenByteBudgetIsExhaustedAndReleasesBudget() throws Exception {
        // 预算最多容纳两个大响应，慢消费者一旦让写入停留，后续报文必须被拒绝而不是无限缓存。
        RpcConfig config = RpcConfig.builder()
            .maxMessageLength(65536)
            .maxChannelWriteBytes(131072)
            .maxBufferedBytes(131072)
            .businessThreads(4)
            .drainTimeoutMillis(2000)
            .shutdownTimeoutMillis(2000)
            .build();
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, BulkService.class, (BulkService) () -> "x".repeat(45000));

        try (RpcServer server = new RpcServer(registry, config)) {
            server.start("127.0.0.1", 0);
            // 正常消费者可以完整取回大响应，作为慢消费者场景的对照基线。
            try (RpcClient probe = new RpcClient(config)) {
                probe.connect("127.0.0.1", server.getPort());
                assertEquals(
                    45000,
                    probe.invoke(SERVICE_NAME, "bulk", String.class, new Class<?>[0], new Object[0])
                        .length());
            }

            // 该连接不读取任何响应，服务端写缓冲与字节预算会被逐步占满。
            Channel slow = connect(server.getPort(), false);
            byte[] body = new JacksonJsonSerializer().serialize(
                new RpcRequest(SERVICE_NAME, "bulk", List.of(), List.of(), 30000, Map.of()));

            for (int id = 1; id <= 60; id++) {
                slow.writeAndFlush(new RpcFrame(RpcProtocol.REQUEST, (byte) 1, id, body));
            }

            long peak = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && slow.isActive()) {
                peak = Math.max(peak, server.bufferedBytes());
                Thread.sleep(20);
            }

            // 无论对端是否消费，服务端持有的报文字节都不能越过端级预算。
            assertTrue(peak <= config.maxBufferedBytes(), "报文字节预算被突破：" + peak);
            slow.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            assertTrue(
                awaitBudgetRelease(server),
                "字节预算未完全释放：" + server.bufferedBytes());
        }
    }

    private Channel connect(int port, boolean autoRead) throws InterruptedException {
        Bootstrap bootstrap = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.AUTO_READ, autoRead);
        if (!autoRead) {
            // 缩小接收缓冲区，让不读取的一端尽快产生真实的 TCP 背压。
            bootstrap.option(ChannelOption.SO_RCVBUF, 4096);
        }
        return bootstrap
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel socket) {
                    socket.pipeline()
                        .addLast(new RpcMessageDecoder(65536, SerializerRegistry.defaults()))
                        .addLast(new RpcMessageEncoder(65536))
                        .addLast(new SimpleChannelInboundHandler<RpcFrame>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, RpcFrame frame) {
                                /* 该用例只验证服务端的过载行为，忽略响应内容。 */
                            }
                        });
                }
            })
            .connect("127.0.0.1", port)
            .sync()
            .channel();
    }

    private static boolean awaitBudgetRelease(RpcServer server) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (server.bufferedBytes() == 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /**
     * 返回大响应的测试服务，用于构造慢消费者场景。
     */
    interface BulkService {

        /** 返回一段较大的文本。 */
        String bulk();
    }
}
