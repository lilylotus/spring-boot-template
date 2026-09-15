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
    final ServiceRegistry registry;
    final RpcConfig config;
    final SerializerRegistry serializers;
    final ThreadPoolExecutor codec;
    final ThreadPoolExecutor business;
    final Semaphore inflight;
    final AtomicBoolean draining = new AtomicBoolean();
    private final EventLoopGroup boss;
    private final EventLoopGroup workers;
    private final ChannelGroup channels;
    final ByteBudget budget;
    private final SslContext ssl;
    private final RpcTelemetry.Resources resources;
    private final AtomicInteger connections = new AtomicInteger();
    private final ConcurrentMap<String, Bucket> rates = new ConcurrentHashMap<>();
    private Channel listener;
    private CompletableFuture<Void> closing;
    private final java.util.List<AutoCloseable> registrations = new java.util.ArrayList<>();

    public RpcServer(ServiceRegistry registry) {
        this(registry, RpcConfig.defaults());
    }

    public RpcServer(ServiceRegistry registry, RpcConfig config) {
        this(registry, config, SerializerRegistry.defaults(), null);
    }

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
     * 监听成功后注册服务，注册失败时回滚整个服务端。
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

    public synchronized int getPort() {
        if (listener == null) {
            throw new IllegalStateException("服务端尚未启动");
        }
        return ((InetSocketAddress) listener.localAddress()).getPort();
    }

    public RpcConfig config() {
        return config;
    }

    public long bufferedBytes() {
        return budget.used();
    }

    boolean rateAllowed(String service) {
        if (!registry.containsService(service)) {
            return true;
        }
        return rates.computeIfAbsent(service, key -> new Bucket()).allow();
    }

    private final class Bucket {
        private double tokens = config.burstCapacity();
        private long updated = System.nanoTime();

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

    private static long remaining(long deadline) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    @Override
    public void close() {
        RpcExecutors.requireExternalThread();
        closeAsync().join();
    }
}
