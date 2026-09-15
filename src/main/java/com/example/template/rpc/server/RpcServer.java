package com.example.template.rpc.server;

import com.example.template.rpc.RpcThreadFactory;
import com.example.template.rpc.protocol.RpcMessageDecoder;
import com.example.template.rpc.protocol.RpcMessageEncoder;
import com.example.template.rpc.protocol.RpcSerializerRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Netty 的 RPC 服务端。
 * <p>
 * <b>关键约束</b>：{@code bossGroup}/{@code workerGroup} 均通过显式传入线程数的
 * {@link NioEventLoopGroup#NioEventLoopGroup(int, java.util.concurrent.ThreadFactory)} 构造，
 * 不使用无参默认构造——默认构造会按 {@code CPU核心数*2} 创建线程，高核数机器上会创建远超实际需要的
 * IO 线程数，浪费资源且加剧上下文切换。IO 线程与业务线程池严格分离：IO 线程只做编解码和读写转发，
 * 真正的业务方法调用在 {@link RpcServerHandler} 里提交给独立的业务线程池执行。
 */
public class RpcServer {

    private static final Logger log = LoggerFactory.getLogger(RpcServer.class);

    /** 业务线程池核心线程数，设计文档给出的默认调优参数。 */
    private static final int BUSINESS_CORE_POOL_SIZE = 16;

    /** 业务线程池最大线程数。 */
    private static final int BUSINESS_MAX_POOL_SIZE = 32;

    /** 业务线程空闲存活时间(秒)。 */
    private static final long BUSINESS_KEEP_ALIVE_SECONDS = 60L;

    /** 业务线程池有界队列容量。 */
    private static final int BUSINESS_QUEUE_CAPACITY = 1000;

    /** 读空闲超时时间(秒)：这段时间内一个字节都没收到(含心跳)，判定连接已失活并关闭。 */
    private static final int READ_IDLE_TIMEOUT_SECONDS = 30;

    /** 优雅停机时等待业务线程池排空的最长时间(秒)，超过则强制中断剩余任务。 */
    private static final long SHUTDOWN_AWAIT_SECONDS = 30L;

    private final int port;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ThreadPoolExecutor businessThreadPool;
    private final ServiceRegistry serviceRegistry = new ServiceRegistry();
    private final RpcSerializerRegistry serializerRegistry = new RpcSerializerRegistry();

    /** 停机标记：置位后仅用于日志/可观测性，真正“不再受理新流量”依赖调用方配合服务发现主动下线。 */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private ChannelFuture serverChannelFuture;

    /**
     * 构造服务端。
     *
     * @param port         监听端口
     * @param workerThreads worker线程数，显式指定，不使用默认构造函数推导出的CPU核心数
     */
    public RpcServer(int port, int workerThreads) {
        this.port = port;
        // 单机场景一个acceptor线程足够处理accept，不需要为boss也分配多线程
        this.bossGroup = new NioEventLoopGroup(1, new RpcThreadFactory("rpc-server-boss", false));
        this.workerGroup = new NioEventLoopGroup(workerThreads, new RpcThreadFactory("rpc-server-worker", false));
        this.businessThreadPool = buildBusinessThreadPool(
            BUSINESS_CORE_POOL_SIZE, BUSINESS_MAX_POOL_SIZE, BUSINESS_KEEP_ALIVE_SECONDS, BUSINESS_QUEUE_CAPACITY);
    }

    /**
     * 注册一个服务实现，须在 {@link #start()} 之前完成。
     *
     * @param interfaceClass 服务接口
     * @param implementation 实现实例
     */
    public void registerService(Class<?> interfaceClass, Object implementation) {
        serviceRegistry.register(interfaceClass, implementation);
    }

    /**
     * 启动服务端并阻塞等待端口绑定完成。
     *
     * @throws InterruptedException 等待绑定过程中被中断
     */
    public void start() throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, 1024)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        .addLast(new IdleStateHandler(READ_IDLE_TIMEOUT_SECONDS, 0, 0, TimeUnit.SECONDS))
                        .addLast(new RpcMessageDecoder(serializerRegistry))
                        .addLast(new RpcMessageEncoder(serializerRegistry))
                        .addLast(new RpcServerHandler(serviceRegistry, businessThreadPool));
                }
            });

        serverChannelFuture = bootstrap.bind(port).sync();
        log.info("RPC服务端已启动，监听端口: {}", boundPort());
    }

    /**
     * 返回实际绑定的端口；当构造时传入 0 让操作系统随机分配端口时，用这个方法拿到真正生效的端口。
     *
     * @return 实际监听端口
     */
    public int boundPort() {
        return ((java.net.InetSocketAddress) serverChannelFuture.channel().localAddress()).getPort();
    }

    /**
     * 优雅停机：标记停机状态 -&gt; 业务线程池排空(超时强制中断) -&gt; 关闭 bossGroup/workerGroup。
     * 不能上来就粗暴断连接，要先让正在处理中的请求有机会跑完(或超时强制结束)。
     *
     * @throws InterruptedException 等待线程池终止过程中被中断
     */
    public void shutdownGracefully() throws InterruptedException {
        shuttingDown.set(true);
        if (serverChannelFuture != null) {
            serverChannelFuture.channel().close();
        }

        businessThreadPool.shutdown();
        if (!businessThreadPool.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("RPC服务端业务线程池在{}秒内未完成排空，强制中断剩余任务", SHUTDOWN_AWAIT_SECONDS);
            businessThreadPool.shutdownNow();
        }

        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /** 仅供单元测试校验IO线程数是否按构造参数显式创建，不对外暴露为公开API。 */
    EventLoopGroup getBossGroup() {
        return bossGroup;
    }

    /** 仅供单元测试校验IO线程数是否按构造参数显式创建，不对外暴露为公开API。 */
    EventLoopGroup getWorkerGroup() {
        return workerGroup;
    }

    /**
     * 构建业务线程池：核心/最大线程数、有界队列、CallerRunsPolicy拒绝策略均按设计文档给出的默认值，
     * 队列打满时让提交任务的线程(即Netty EventLoop线程)自己执行该任务，起到自然的限流/背压效果，
     * 而不是无界堆积任务把内存打爆。抽成包内可见的静态方法，方便单元测试用更小的参数验证拒绝策略。
     *
     * @param corePoolSize      核心线程数
     * @param maximumPoolSize   最大线程数
     * @param keepAliveSeconds  空闲线程存活时间(秒)
     * @param queueCapacity     有界队列容量
     * @return 配置好的业务线程池
     */
    static ThreadPoolExecutor buildBusinessThreadPool(
        int corePoolSize, int maximumPoolSize, long keepAliveSeconds, int queueCapacity) {
        return new ThreadPoolExecutor(
            corePoolSize, maximumPoolSize,
            keepAliveSeconds, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            new RpcThreadFactory("rpc-business", false),
            new ThreadPoolExecutor.CallerRunsPolicy());
    }

}
