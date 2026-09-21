package org.example.simple.rpc;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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

import org.example.simple.rpc.common.JacksonJsonSerializer;
import org.example.simple.rpc.common.MessageSerializer;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 序列化转换的有界执行器准入测试。
 * <p>
 * 用一个可阻塞的序列化器占住解码线程，验证解码队列满时的拒绝路径会关闭连接，
 * 并且被拒绝和被阻塞的报文最终都归还字节预算，不会泄漏已接收的缓冲区。
 */
class RpcCodecAdmissionTest {

    private static final String SERVICE_NAME = "回声服务";

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
    void rejectsFramesWhenCodecQueueIsFullAndReleasesEveryBuffer() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .codecThreads(1)
            .codecQueueCapacity(1)
            .heartbeatIntervalMillis(5000)
            .readIdleTimeoutMillis(20000)
            .drainTimeoutMillis(2000)
            .shutdownTimeoutMillis(2000)
            .build();
        BlockingSerializer serializer = new BlockingSerializer();
        ServiceRegistry services = new ServiceRegistry();
        services.register(SERVICE_NAME, EchoService.class, (EchoService) () -> "回声");

        try (RpcServer server =
                 new RpcServer(services, config, new SerializerRegistry(serializer), null)) {
            server.start("127.0.0.1", 0);
            byte[] body = new JacksonJsonSerializer().serialize(request());

            try (Raw raw = new Raw(server.getPort())) {
                for (int id = 1; id <= 10; id++) {
                    raw.send(new RpcFrame(RpcProtocol.REQUEST, (byte) 1, id, body));
                }

                // 解码线程被占满且队列已满时，多余报文只能被拒绝并关闭连接。
                assertTrue(
                    raw.channel.closeFuture().await(5, TimeUnit.SECONDS),
                    "连接未因解码队列满而关闭，当前预算占用：" + server.bufferedBytes());
            }

            serializer.release();
            assertTrue(awaitBudgetRelease(server), "字节预算未完全释放：" + server.bufferedBytes());
            assertEquals(0, server.bufferedBytes());
        }
    }

    private static boolean awaitBudgetRelease(RpcServer server) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (server.bufferedBytes() == 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static RpcRequest request() {
        return new RpcRequest(SERVICE_NAME, "echo", List.of(), List.of(), 30000, Map.of());
    }

    /**
     * 测试用回声服务。
     */
    interface EchoService {

        /** 返回固定回声内容。 */
        String echo();
    }

    /**
     * 可阻塞的 JSON 序列化器，用于确定性地占满解码线程。
     */
    private static final class BlockingSerializer implements MessageSerializer {

        private final MessageSerializer delegate = new JacksonJsonSerializer();
        private final CountDownLatch blocked = new CountDownLatch(1);

        void release() {
            blocked.countDown();
        }

        @Override
        public byte id() {
            return delegate.id();
        }

        @Override
        public byte[] serialize(Object value, Type type) {
            return delegate.serialize(value, type);
        }

        @Override
        public Object deserialize(byte[] bytes, Type type) {
            try {
                blocked.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return delegate.deserialize(bytes, type);
        }
    }

    /**
     * 不安装框架管线的裸连接，只做帧编解码。
     */
    private final class Raw implements AutoCloseable {

        private final Channel channel;

        Raw(int port) throws InterruptedException {
            channel = new Bootstrap()
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
                                    /* 该用例只验证服务端的准入与释放，忽略响应内容。 */
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
