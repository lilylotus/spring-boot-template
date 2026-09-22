package org.example.simple.rpc.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import org.example.simple.rpc.common.MessageSerializer;
import org.example.simple.rpc.common.SerializerRegistry;
import org.example.simple.rpc.config.RpcConfig;
import org.example.simple.rpc.monitoring.RpcTelemetry;
import org.example.simple.rpc.transport.ByteBudget;
import org.example.simple.rpc.transport.RpcExecutors;
import org.example.simple.rpc.transport.RpcPipeline;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 具有固定线程配置、有界准入与限时排空的 RPC 服务端。
 */
public final class RpcServer implements AutoCloseable {
    /** 本地服务注册表，保存可被远程调用的服务实现及其方法元数据。 */
    final ServiceRegistry registry;

    /** 不可变的服务端运行参数，线程数、超时与各类上限均取自此配置。 */
    final RpcConfig config;

    /** 序列化器注册表，按请求头中的序列化标识选择对应实现。 */
    final SerializerRegistry serializers;

    /** 编解码线程池，固定线程数与有界队列，承担请求解码与响应编码。 */
    final ThreadPoolExecutor codec;

    /** 业务线程池，固定线程数与有界队列，执行被调用的服务方法。 */
    final ThreadPoolExecutor business;

    /** 在途请求准入信号量，许可数为业务线程数与业务队列容量之和，取不到许可即拒绝请求。 */
    final Semaphore inflight;

    /** 排空标记，置位后拒绝新连接与新请求，只允许已受理的请求收尾。 */
    final AtomicBoolean draining = new AtomicBoolean();

    /** 只负责接受新连接的 boss 事件循环组。 */
    private final EventLoopGroup boss;

    /** 负责已建立连接读写的 worker 事件循环组。 */
    private final EventLoopGroup workers;

    /** 已接受的客户端 Channel 集合，关闭时统一排空并关闭。 */
    private final ChannelGroup channels;

    /** 出站缓冲字节预算，限制全部连接累计堆积的待写字节数。 */
    final ByteBudget budget;

    /** TLS 上下文；为 {@code null} 时使用明文传输。 */
    private final SslContext ssl;

    /** 遥测资源句柄，注册在途请求数、连接数、队列长度与缓冲字节等指标，关闭时注销。 */
    private final RpcTelemetry.Resources resources;

    /** 当前已建立的客户端连接数，用于与 {@code maxServerConnections} 比较做连接准入。 */
    private final AtomicInteger connections = new AtomicInteger();

    /** 按服务名维度缓存的令牌桶限流器。 */
    private final ConcurrentMap<String, Bucket> rates = new ConcurrentHashMap<>();

    /** 服务端监听 Channel；为 {@code null} 表示尚未启动，读写由实例锁保护。 */
    private Channel listener;

    /** 关闭流程的幂等结果，首次调用 {@link #closeAsync()} 时创建，非 {@code null} 表示已进入关闭流程。 */
    private CompletableFuture<Void> closing;

    /** 服务发现注册句柄列表，关闭时逐个反注册，读写由实例锁保护。 */
    private final java.util.List<AutoCloseable> registrations = new java.util.ArrayList<>();

    /**
     * 使用默认生产参数与默认序列化器创建服务端。
     *
     * @param registry 已注册服务的本地注册表
     */
    public RpcServer(ServiceRegistry registry) {
        this(registry, RpcConfig.defaults());
    }

    /**
     * 使用指定生产参数创建服务端，序列化器取默认注册表，不启用 TLS。
     *
     * @param registry 已注册服务的本地注册表
     * @param config 生产参数
     */
    public RpcServer(ServiceRegistry registry, RpcConfig config) {
        this(registry, config, SerializerRegistry.defaults(), null);
    }

    /**
     * 以少量关键参数创建服务端，其余参数取默认值，便于测试与快速验证。
     *
     * @param registry 已注册服务的本地注册表
     * @param serializer 唯一启用的序列化器
     * @param maxLength 最大消息体字节数
     * @param threads 业务线程数
     * @param queue 业务队列容量
     */
    public RpcServer(
        ServiceRegistry registry,
        MessageSerializer serializer,
        int maxLength,
        int threads,
        int queue) {
        this(
            registry,
            RpcConfig.builder()
                .maxMessageLength(maxLength)
                .businessThreads(threads)
                .businessQueueCapacity(queue)
                .build(),
            new SerializerRegistry(serializer),
            null);
    }

    /**
     * 完整参数构造服务端：创建事件循环组、线程池、准入信号量与字节预算，并注册遥测指标。
     *
     * <p>构造阶段只分配资源，不监听端口；在途请求许可数取"业务线程数 + 业务队列容量"，
     * 使准入上限与业务池实际吞吐能力一致。
     *
     * @param registry 已注册服务的本地注册表
     * @param config 生产参数
     * @param serializers 序列化器注册表
     * @param ssl TLS 上下文；为 {@code null} 时使用明文传输
     */
    public RpcServer(
        ServiceRegistry registry,
        RpcConfig config,
        SerializerRegistry serializers,
        SslContext ssl) {
        this.registry = Objects.requireNonNull(registry);
        this.config = Objects.requireNonNull(config);
        this.serializers = Objects.requireNonNull(serializers);
        this.ssl = ssl;
        boss =
            new MultiThreadIoEventLoopGroup(
                config.serverBossThreads(),
                RpcExecutors.factory("boss"),
                NioIoHandler.newFactory());
        workers =
            new MultiThreadIoEventLoopGroup(
                config.serverWorkerThreads(),
                RpcExecutors.factory("worker"),
                NioIoHandler.newFactory());
        channels = new DefaultChannelGroup(workers.next());
        codec =
            RpcExecutors.pool(
                "server-codec", config.codecThreads(), config.codecQueueCapacity());
        business =
            RpcExecutors.pool(
                "business", config.businessThreads(), config.businessQueueCapacity());
        inflight = new Semaphore(config.businessThreads() + config.businessQueueCapacity());
        budget = new ByteBudget(config.maxBufferedBytes());
        resources =
            RpcTelemetry.bind(
                "server",
                () ->
                    config.businessThreads()
                        + config.businessQueueCapacity()
                        - inflight.availablePermits(),
                connections::get,
                () -> codec.getQueue().size() + business.getQueue().size(),
                budget::used);
    }

    /**
     * 绑定端口开始对外提供服务。
     *
     * <p>连接建立时依次判断：正在停机则直接关闭；连接数超过上限则关闭并回退计数；
     * 通过后登记到连接组并安装 RPC 管线。绑定或初始化失败会触发整体关闭，避免半启动状态残留资源。
     *
     * <p>该方法会阻塞等待绑定结果，因此禁止在框架线程上调用。
     *
     * @param host 监听地址
     * @param port 监听端口，传 0 由系统分配
     * @throws IllegalStateException 已启动或已进入停机流程时抛出
     * @throws InterruptedException 等待绑定期间被中断时抛出
     */
    public synchronized void start(String host, int port) throws InterruptedException {
        RpcExecutors.requireExternalThread();
        if (listener != null || draining.get()) {
            throw new IllegalStateException("服务端已启动或关闭");
        }
        try {
            listener =
                new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, config.backlog())
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, config.waterMark())
                    .childOption(ChannelOption.AUTO_READ, true)
                    .childOption(ChannelOption.ALLOW_HALF_CLOSURE, false)
                    .childHandler(
                        new ChannelInitializer<SocketChannel>() {
                            @Override
                            /**
                             * 对新接入的连接做准入判断，通过后安装 RPC 管线。
                             *
                             * <p>停机中或连接数超过上限时直接关闭连接，并回退已累加的连接计数。
                             *
                             * @param channel 新接入的客户端连接
                             */
                            protected void initChannel(SocketChannel channel) {
                                if (draining.get()) {
                                    channel.close();
                                    return;
                                }
                                if (connections.incrementAndGet()
                                    > config.maxServerConnections()) {
                                    connections.decrementAndGet();
                                    channel.close();
                                    return;
                                }
                                channels.add(channel);
                                channel.closeFuture()
                                    .addListener(
                                        done -> connections.decrementAndGet());
                                RpcPipeline.install(channel, config, serializers, budget, ssl,
                                    null, 0, new RpcServerHandler(RpcServer.this));
                            }
                        })
                    .bind(host, port)
                    .sync()
                    .channel();
        } catch (InterruptedException | RuntimeException error) {
            closeAsync();
            throw error;
        }
    }

    /**
     * 启动服务端并把指定服务注册到注册中心。
     *
     * <p>先监听成功再注册，保证注册中心可见的实例一定已经可以接收请求；
     * 注册过程中任一服务失败则整体关闭服务端，避免出现"端口已监听但注册不完整"的中间状态。
     * 注册前校验服务已在本地注册表中，防止对外暴露本进程并不提供的服务。
     *
     * @param host 监听地址
     * @param port 监听端口，传 0 由系统分配
     * @param advertisedHost 向注册中心公布的可达地址，可与监听地址不同（如容器内外地址差异）
     * @param discovery 注册中心适配器
     * @param services 需要注册的服务名
     * @throws IllegalArgumentException 服务尚未在本地注册时抛出
     * @throws InterruptedException 等待绑定期间被中断时抛出
     */
    public void startRegistered(
        String host,
        int port,
        String advertisedHost,
        org.example.simple.rpc.discovery.NacosServiceDiscovery discovery,
        String... services)
        throws InterruptedException {
        start(host, port);
        try {
            synchronized (this) {
                for (String service : services) {
                    if (!registry.containsService(service)) {
                        throw new IllegalArgumentException("服务尚未本地注册");
                    }
                    registrations.add(
                        discovery.register(
                            service,
                            new org.example.simple.rpc.discovery.ServiceInstance(
                                advertisedHost + ":" + getPort(),
                                advertisedHost,
                                getPort(),
                                1)));
                }
            }
        } catch (RuntimeException error) {
            closeAsync();
            throw error;
        }
    }

    /**
     * 返回实际监听端口，用于监听端口为 0 时获取系统分配的端口。
     *
     * @return 实际监听端口
     * @throws IllegalStateException 服务端尚未启动时抛出
     */
    public synchronized int getPort() {
        if (listener == null) {
            throw new IllegalStateException("服务端尚未启动");
        }
        return ((InetSocketAddress) listener.localAddress()).getPort();
    }

    /**
     * 返回本服务端使用的生产参数。
     *
     * @return 不可变的生产参数
     */
    public RpcConfig config() {
        return config;
    }

    /**
     * 返回当前被框架持有的报文字节数，用于排查内存水位与背压情况。
     *
     * @return 当前占用的报文字节数
     */
    public long bufferedBytes() {
        return budget.used();
    }

    /**
     * 判断某个服务当前是否允许接收新请求。
     *
     * <p>未注册的服务直接放行，交由后续分发阶段返回"服务未找到"，避免限流器为无效服务名创建状态。
     *
     * @param service 服务名
     * @return 允许接收时为 {@code true}
     */
    boolean rateAllowed(String service) {
        if (!registry.containsService(service)) {
            return true;
        }
        return rates.computeIfAbsent(service, key -> new Bucket()).allow();
    }

    /** 单个服务的令牌桶限流器，按配置的速率补充令牌、按突发容量设置上限。 */
    private final class Bucket {
        /** 当前可用令牌数，初始为配置的突发容量，按速率随时间补充。 */
        private double tokens = config.burstCapacity();

        /** 上次补充令牌的时间戳，单位为纳秒，用于计算本次应补充的令牌量。 */
        private long updated = System.nanoTime();

        /**
         * 尝试取用一个令牌。
         *
         * <p>按距上次取用的时间差补充令牌（不超过突发容量），令牌不足 1 则拒绝。
         * 这样既能限制平均速率，又允许短时突发。
         *
         * @return 取到令牌时为 {@code true}
         */
        synchronized boolean allow() {
            long now = System.nanoTime();
            tokens =
                Math.min(
                    config.burstCapacity(),
                    tokens + (now - updated) / 1e9 * config.requestsPerSecond());
            updated = now;
            if (tokens < 1) {
                return false;
            }
            tokens--;
            return true;
        }
    }

    /**
     * 异步关闭服务端，按"停止接入 → 排空在途 → 释放资源"的顺序收尾。
     *
     * <p>关闭在独立线程执行，步骤如下：置排空标记拒绝新连接与新请求；关闭监听端口；
     * 从注册中心反注册（单个反注册也受排空截止时间约束，避免注册中心卡住停机）；
     * 依次停止解码与业务线程池并限时等待在途任务结束；追平各连接已排队的写任务并等待出站缓冲清空，
     * 确保已受理的响应真正发出；随后关闭连接、停止线程池与事件循环组，最后注销遥测资源。
     *
     * <p>排空与关闭分别受 {@code drainTimeoutMillis} 与 {@code shutdownTimeoutMillis} 约束，
     * 保证停机时间有上界。重复调用返回同一个结果，因此可安全地被并发或重复调用。
     *
     * @return 关闭完成的结果；关闭过程中出现异常时以异常完成
     */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing;
        }
        draining.set(true);
        closing = new CompletableFuture<>();
        RpcExecutors.factory("server-close")
            .newThread(
                () -> {
                    try {
                        long drainDeadline =
                            System.nanoTime()
                                + TimeUnit.MILLISECONDS.toNanos(
                                config.drainTimeoutMillis());
                        if (listener != null) {
                            listener.close();
                        }
                        for (AutoCloseable registration : registrations) {
                            FutureTask<Void> unregister =
                                new FutureTask<>(
                                    () -> {
                                        registration.close();
                                        return null;
                                    });
                            RpcExecutors.factory("unregister")
                                .newThread(unregister)
                                .start();
                            try {
                                unregister.get(
                                    remaining(drainDeadline), TimeUnit.MILLISECONDS);
                            } catch (Exception error) {
                                unregister.cancel(true);
                            }
                        }
                        codec.shutdown();
                        codec.awaitTermination(
                            remaining(drainDeadline), TimeUnit.MILLISECONDS);
                        business.shutdown();
                        business.awaitTermination(
                            remaining(drainDeadline), TimeUnit.MILLISECONDS);
                        // 业务线程结束时，响应可能刚提交写入还没刷出。这里追加一次空写入并等待其
                        // 完成：Netty 保证写入按顺序完成，因此它完成时前序响应已经真正发出，
                        // 否则关闭子 Channel 会把已受理的在途响应直接丢弃。
                        // 业务线程结束时，响应可能只是刚被提交到事件循环、还没真正刷出。
                        // 先让事件循环追平已排队的写入任务，再等待出站缓冲清空，
                        // 否则关闭子 Channel 会把已经受理的在途响应直接丢弃。
                        for (Channel channel : channels) {
                            channel.eventLoop()
                                .submit(() -> {
                                })
                                .awaitUninterruptibly(remaining(drainDeadline));
                            while (channel.isActive()
                                && channel.bytesBeforeUnwritable() < config.writeHighWaterMark()
                                && System.nanoTime() < drainDeadline) {
                                Thread.sleep(5);
                            }
                        }
                        long closeDeadline =
                            System.nanoTime()
                                + TimeUnit.MILLISECONDS.toNanos(
                                config.shutdownTimeoutMillis());
                        channels.close().awaitUninterruptibly(remaining(closeDeadline));
                        RpcExecutors.stop(codec);
                        RpcExecutors.stop(business);
                        boss.shutdownGracefully(
                            0, remaining(closeDeadline), TimeUnit.MILLISECONDS);
                        workers.shutdownGracefully(
                                0, remaining(closeDeadline), TimeUnit.MILLISECONDS)
                            .awaitUninterruptibly(remaining(closeDeadline));
                        resources.close();
                        closing.complete(null);
                    } catch (Throwable error) {
                        closing.completeExceptionally(error);
                    }
                })
            .start();
        return closing;
    }

    /**
     * 计算距截止时间的剩余毫秒数。
     *
     * @param deadline 截止时间的纳秒时间戳
     * @return 剩余毫秒数，至少为 1，避免传入 0 被等待方当作"无限等待"
     */
    private static long remaining(long deadline) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    /**
     * 同步关闭服务端，阻塞直到关闭流程结束。
     *
     * @throws IllegalStateException 在框架线程上调用时抛出，避免阻塞等待自身造成死锁
     */
    @Override
    public void close() {
        RpcExecutors.requireExternalThread();
        closeAsync().join();
    }
}
