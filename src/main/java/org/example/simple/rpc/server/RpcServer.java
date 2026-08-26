package org.example.simple.rpc.server;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import org.example.simple.rpc.common.JacksonJsonSerializer;
import org.example.simple.rpc.common.MessageSerializer;
import org.example.simple.rpc.common.RpcMessageDecoder;
import org.example.simple.rpc.common.RpcMessageEncoder;
import org.example.simple.rpc.common.RpcProtocol;
import org.example.simple.rpc.common.RpcRequest;
import org.example.simple.rpc.common.RpcResponse;

/**
 * 基于 Netty 的 JSON RPC 服务端。
 */
public final class RpcServer implements AutoCloseable {

    private final MessageSerializer serializer;
    private final RpcRequestDispatcher dispatcher;
    private final int maxMessageLength;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ThreadPoolExecutor businessExecutor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Channel serverChannel;

    /**
     * 使用默认配置创建服务端。
     *
     * @param serviceRegistry 已完成服务注册的注册表
     */
    public RpcServer(ServiceRegistry serviceRegistry) {
        this(
            serviceRegistry,
            new JacksonJsonSerializer(),
            RpcProtocol.DEFAULT_MAX_MESSAGE_LENGTH,
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            1024);
    }

    /**
     * 使用指定配置创建服务端。
     *
     * @param serviceRegistry 服务注册表
     * @param serializer 消息序列化器
     * @param maxMessageLength 最大消息体字节数
     * @param businessThreads 业务线程数
     * @param businessQueueCapacity 业务等待队列容量
     */
    public RpcServer(
        ServiceRegistry serviceRegistry,
        MessageSerializer serializer,
        int maxMessageLength,
        int businessThreads,
        int businessQueueCapacity) {
        this.serializer = Objects.requireNonNull(serializer, "消息序列化器不能为空");
        this.dispatcher = new RpcRequestDispatcher(serviceRegistry, serializer);
        if (maxMessageLength <= 0 || businessThreads <= 0 || businessQueueCapacity <= 0) {
            throw new IllegalArgumentException("消息长度、业务线程数和队列容量必须大于零");
        }
        this.maxMessageLength = maxMessageLength;
        this.bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        this.businessExecutor = new ThreadPoolExecutor(
            businessThreads,
            businessThreads,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(businessQueueCapacity),
            new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 启动服务端并监听指定地址。
     *
     * @param host 监听主机
     * @param port 监听端口，传入 0 时由系统分配
     * @throws InterruptedException 当前线程等待绑定时被中断
     */
    public void start(String host, int port) throws InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("RPC 服务端已关闭");
        }
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("RPC 服务端已经启动");
        }
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                            .addLast(new RpcMessageDecoder<>(RpcRequest.class, serializer, maxMessageLength))
                            .addLast(new RpcMessageEncoder<>(RpcResponse.class, serializer, maxMessageLength))
                            .addLast(new RpcServerHandler(dispatcher, businessExecutor));
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true);
            serverChannel = bootstrap.bind(host, port).sync().channel();
        } catch (InterruptedException | RuntimeException exception) {
            started.set(false);
            close();
            throw exception;
        }
    }

    /**
     * 获取服务端实际监听端口。
     *
     * @return 实际监听端口
     */
    public int getPort() {
        Channel channel = serverChannel;
        if (channel == null) {
            throw new IllegalStateException("RPC 服务端尚未启动");
        }
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /**
     * 幂等关闭监听通道、业务执行器和事件循环组。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Channel channel = serverChannel;
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        businessExecutor.shutdownNow();
        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
