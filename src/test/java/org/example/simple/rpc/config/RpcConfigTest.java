package org.example.simple.rpc.config;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.Test;

import org.example.simple.rpc.common.SerializerRegistry;
import org.example.simple.rpc.transport.ByteBudget;
import org.example.simple.rpc.transport.RpcExecutors;
import org.example.simple.rpc.transport.RpcPipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产参数测试。
 * <p>
 * 在真实 NIO 连接上验证默认参数与覆盖参数确实生效，并覆盖选项未生效、
 * 非法参数组合、参数溢出以及固定线程默认值等失败与边界路径。
 */
class RpcConfigTest {

    @Test
    void appliesDefaultTimeoutsToRealChannelPipeline() throws Exception {
        RpcConfig config = RpcConfig.defaults();

        try (Peer peer = new Peer(config)) {
            Channel channel = peer.install(config);
            IdleStateHandler idle = (IdleStateHandler) channel.pipeline().get("idle");

            assertEquals(45000, idle.getReaderIdleTimeInMillis());
            assertEquals(15000, idle.getWriterIdleTimeInMillis());
            assertEquals(0, idle.getAllIdleTimeInMillis());
            // 索引 0 是建立连接时的占位处理器，框架管线追加在其后。
            assertEquals(
                List.of("budget", "idle", "writeTimeout", "frames", "encoder", "heartbeat", "rpc"),
                channel.pipeline().names().subList(1, 8));
        }
    }

    @Test
    void appliesOverriddenParametersToRealChannel() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .readIdleTimeoutMillis(9000)
            .heartbeatIntervalMillis(3000)
            .writeLowWaterMark(8192)
            .writeHighWaterMark(16384)
            .build();

        try (Peer peer = new Peer(config)) {
            Channel channel = peer.install(config);
            IdleStateHandler idle = (IdleStateHandler) channel.pipeline().get("idle");

            assertEquals(9000, idle.getReaderIdleTimeInMillis());
            assertEquals(3000, idle.getWriterIdleTimeInMillis());
            assertEquals(8192, channel.config().getWriteBufferWaterMark().low());
            assertEquals(16384, channel.config().getWriteBufferWaterMark().high());
            assertEquals(
                PooledByteBufAllocator.DEFAULT,
                channel.config().getOption(ChannelOption.ALLOCATOR));
        }
    }

    @Test
    void failsFastWhenChannelOptionWasNotApplied() throws Exception {
        RpcConfig config = RpcConfig.defaults();

        try (Peer peer = new Peer(config)) {
            peer.tcpNoDelay = false;
            Channel channel = peer.connect();

            IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> RpcPipeline.install(
                    channel,
                    config,
                    SerializerRegistry.defaults(),
                    new ByteBudget(config.maxBufferedBytes()),
                    null,
                    null,
                    0,
                    new ChannelInboundHandlerAdapter()));

            assertTrue(error.getMessage().contains("TCP_NODELAY"));
        }
    }

    @Test
    void failsFastWhenWaterMarkDoesNotMatchConfiguration() throws Exception {
        RpcConfig config = RpcConfig.defaults();

        try (Peer peer = new Peer(config)) {
            // 连接按另一组水位建立，安装管线时必须发现参数未生效而不是继续运行。
            Channel channel = peer.connect();
            RpcConfig expected = RpcConfig.builder()
                .writeLowWaterMark(8192)
                .writeHighWaterMark(16384)
                .build();

            IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> RpcPipeline.install(
                    channel,
                    expected,
                    SerializerRegistry.defaults(),
                    new ByteBudget(expected.maxBufferedBytes()),
                    null,
                    null,
                    0,
                    new ChannelInboundHandlerAdapter()));

            assertTrue(error.getMessage().contains("WRITE_BUFFER_WATER_MARK"));
        }
    }

    @Test
    void usesFixedThreadDefaultsInsteadOfCpuDerivedValues() {
        RpcConfig defaults = RpcConfig.defaults();

        assertEquals(1, defaults.serverBossThreads());
        assertEquals(4, defaults.serverWorkerThreads());
        assertEquals(2, defaults.clientIoThreads());
        assertEquals(8, defaults.businessThreads());
        assertEquals(2, defaults.codecThreads());
        assertEquals(2, defaults.completionThreads());
        assertEquals(256, defaults.businessQueueCapacity());
        assertEquals(256, defaults.codecQueueCapacity());
    }

    @Test
    void rejectsNonPositiveThreadCounts() {
        assertThrows(IllegalArgumentException.class, () -> RpcConfig.builder().serverBossThreads(0).build());
        assertThrows(IllegalArgumentException.class, () -> RpcConfig.builder().serverWorkerThreads(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RpcConfig.builder().clientIoThreads(0).build());
        assertThrows(IllegalArgumentException.class, () -> RpcConfig.builder().businessThreads(-8).build());
        assertThrows(IllegalArgumentException.class, () -> RpcConfig.builder().codecThreads(0).build());
        assertThrows(IllegalArgumentException.class, () -> RpcConfig.builder().completionThreads(0).build());
    }

    @Test
    void rejectsIllegalParameterCombinations() {
        assertThrows(
            IllegalArgumentException.class,
            () -> RpcConfig.builder().writeLowWaterMark(65536).writeHighWaterMark(32768).build());
        assertThrows(
            IllegalArgumentException.class,
            () -> RpcConfig.builder().writeHighWaterMark(65536).maxChannelWriteBytes(32768).build());
        assertThrows(
            IllegalArgumentException.class,
            () -> RpcConfig.builder().maxMessageLength(8388608).maxChannelWriteBytes(8388608).build());
        assertThrows(
            IllegalArgumentException.class,
            () -> RpcConfig.builder().maxMessageLength(8388608).maxBufferedBytes(8388608).build());
        assertThrows(
            IllegalArgumentException.class,
            () -> RpcConfig.builder().heartbeatIntervalMillis(45000).readIdleTimeoutMillis(45000).build());
    }

    @Test
    void rejectsParameterOverflow() {
        assertThrows(
            IllegalArgumentException.class,
            () -> RpcConfig.builder().maxMessageLength(Integer.MAX_VALUE).build());
        assertThrows(
            ArithmeticException.class,
            () -> RpcConfig.builder()
                .businessThreads(Integer.MAX_VALUE)
                .businessQueueCapacity(Integer.MAX_VALUE)
                .build());
    }

    /**
     * 提供一个真实的 TCP 对端和按生产选项建立的客户端连接。
     */
    private static final class Peer implements AutoCloseable {

        private final ServerSocket acceptor = new ServerSocket(0);
        private final EventLoopGroup group;
        private final RpcConfig config;

        /** 建立连接时是否应用 {@code TCP_NODELAY}，用于构造选项未生效场景。 */
        private boolean tcpNoDelay = true;

        private Channel channel;

        Peer(RpcConfig config) throws Exception {
            this.config = config;
            group = new MultiThreadIoEventLoopGroup(
                1, RpcExecutors.factory("test-io"), NioIoHandler.newFactory());
        }

        Channel connect() throws InterruptedException {
            channel = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, tcpNoDelay)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, config.waterMark())
                .option(ChannelOption.AUTO_READ, true)
                .option(ChannelOption.ALLOW_HALF_CLOSURE, false)
                .handler(new ChannelInboundHandlerAdapter())
                .connect("127.0.0.1", acceptor.getLocalPort())
                .sync()
                .channel();
            return channel;
        }

        Channel install(RpcConfig applied) throws InterruptedException {
            Channel connected = connect();
            RpcPipeline.install(
                connected,
                applied,
                SerializerRegistry.defaults(),
                new ByteBudget(applied.maxBufferedBytes()),
                null,
                null,
                0,
                new ChannelInboundHandlerAdapter());
            return connected;
        }

        @Override
        public void close() throws Exception {
            if (channel != null) {
                channel.close().awaitUninterruptibly(1, TimeUnit.SECONDS);
            }
            group.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS)
                .awaitUninterruptibly(2, TimeUnit.SECONDS);
            acceptor.close();
        }
    }
}
