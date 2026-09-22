package org.example.simple.rpc.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.*;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;

import org.example.simple.rpc.common.*;
import org.example.simple.rpc.config.RpcConfig;
import org.example.simple.rpc.discovery.*;
import org.example.simple.rpc.loadbalance.*;
import org.example.simple.rpc.monitoring.RpcTelemetry;
import org.example.simple.rpc.transport.*;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 多连接 RPC 客户端；所有调用共享请求表和时间轮。 */
public final class RpcClient implements AutoCloseable {
    /** 对外公开的默认调用超时时间，单位为毫秒；实际生效值取自 {@link RpcConfig#requestTimeoutMillis()}。 */
    public static final long DEFAULT_TIMEOUT_MILLIS = 5000;

    /** 不可变的客户端运行参数，线程数、超时与各类上限均取自此配置。 */
    private final RpcConfig config;

    /** 序列化器注册表，按序列化标识选择请求编码与响应解码实现。 */
    private final SerializerRegistry serializers;

    /** 调用未显式指定序列化器时使用的默认序列化标识。 */
    private final byte defaultSerializer;

    /** TLS 上下文；为 {@code null} 时使用明文传输。 */
    private final SslContext ssl;

    /** 所有连接共享的 I/O 事件循环组，负责连接建立与网络读写。 */
    private final EventLoopGroup loops;

    /** 编解码线程池，固定线程数与有界队列，承担请求编码、响应解码与断连事件的排序。 */
    private final ThreadPoolExecutor codec;

    /** 结果完成线程池，在此线程池内回调用户 future，避免用户代码阻塞 I/O 线程。 */
    private final ThreadPoolExecutor completions;

    /** 单线程调度器，串行处理服务发现事件、实例变更与断线重连，保证发现事件顺序一致。 */
    private final ScheduledExecutorService discoveryExecutor;

    /** 单线程调度器，专门用于重试前的退避等待，避免退避阻塞 I/O、时间轮或发现线程。 */
    private final ScheduledExecutorService retryExecutor;

    /** 重试调用的结果完成线程池，与普通调用的完成线程池隔离，防止重试回调相互阻塞。 */
    private final ThreadPoolExecutor retryCompletions;

    /** 带重试调用的准入信号量，许可数为最大在途请求数，取不到许可即以服务繁忙拒绝。 */
    private final Semaphore retrySlots;

    /** 在途重试调用的终止回调表，键为对外返回的 future，停机时逐个触发以快速失败。 */
    private final ConcurrentMap<CompletableFuture<?>, Runnable> retryClosers =
            new ConcurrentHashMap<>();

    /** 共享时间轮，统一驱动单次调用超时与重试调用的总时间预算。 */
    private final HashedWheelTimer timer;

    /** 出站缓冲字节预算，限制全部连接累计堆积的待写字节数。 */
    private final ByteBudget budget;

    /** 单次调用的在途准入信号量，许可数为最大在途请求数，调用完成后释放。 */
    private final Semaphore slots;

    /** 遥测资源句柄，注册在途请求数、连接数、队列长度与缓冲字节等指标，关闭时注销。 */
    private final RpcTelemetry.Resources resources;

    /** 在途请求表，键为请求编号，用于把响应、超时与断连回填到对应调用。 */
    private final ConcurrentMap<Long, Pending> pending = new ConcurrentHashMap<>();

    /** 请求编号生成器，单调递增且不回绕，耗尽到 {@link Long#MAX_VALUE} 时拒绝新调用。 */
    private final AtomicLong ids = new AtomicLong();

    /** 连接表，键为“服务名/实例标识”，值为对应的连接端点，读写由实例锁保护。 */
    private final Map<String, Endpoint> endpoints = new HashMap<>();

    /** 服务发现订阅句柄表，键为服务名，关闭时逐个退订，读写由实例锁保护。 */
    private final Map<String, AutoCloseable> subscriptions = new HashMap<>();

    /** 服务发现失败的起始时间戳（纳秒），键为服务名；失败超过 30 秒则判定实例缓存过期。 */
    private final Map<String, Long> discoveryFailure = new HashMap<>();

    /** 按服务名维度缓存的负载均衡器实例，保证同一服务的选路状态连续。 */
    private final Map<String, LoadBalancer> balancers = new HashMap<>();

    /** 负载均衡器工厂，为每个新服务创建独立的均衡器实例。 */
    private final java.util.function.Supplier<LoadBalancer> balancerFactory;

    /** 排空标记，置位后拒绝新调用、新连接与迟到的发现事件，读写由实例锁保护。 */
    private boolean draining;

    /** 关闭流程的幂等结果，首次调用 {@link #closeAsync()} 时创建，非 {@code null} 表示已进入关闭流程。 */
    private CompletableFuture<Void> closing;

    /** 一个服务实例对应的连接端点，聚合实例信息、连接状态与连接级准入。 */
    private final class Endpoint {
        /** 该连接服务的服务名；直连方式创建的连接使用通配符 {@code "*"}。 */
        final String service;

        /** 对应的服务实例信息，包含实例标识、主机、端口与权重。 */
        final ServiceInstance instance;

        /** 连接级在途请求许可，许可数为单连接最大在途请求数，用于避免单连接过载。 */
        final Semaphore requests = new Semaphore(config.maxRequestsPerConnection());

        /** 已建立的 Channel；为 {@code null} 表示尚未连接成功。 */
        volatile Channel channel;

        /** 实例下线标记，置位后不再参与选路，并在排空超时后关闭连接。 */
        volatile boolean removed;

        /** 连接建立中标记，防止同一端点并发发起多次连接，读写由客户端实例锁保护。 */
        boolean connecting;

        /** 连续连接失败次数，用于计算重连退避时长，连接成功后归零。 */
        int failures;

        Endpoint(String service, ServiceInstance instance) {
            this.service = service;
            this.instance = instance;
        }
    }

    /** 一次已登记的在途调用，聚合结果、路由、类型与超时信息。 */
    private final class Pending {
        /** 请求编号，与响应报文中的编号一一对应。 */
        final long id;

        /** 对外返回的调用结果，由完成线程池负责最终完成。 */
        final CompletableFuture<Object> future = new CompletableFuture<>();

        /** 本次调用选中的连接端点，调用结束时归还其连接级在途许可。 */
        final Endpoint endpoint;

        /** 发送本次请求的 Channel，断连时据此判断哪些在途调用需要失败。 */
        final Channel channel;

        /** 期望的返回值类型，供响应解码时反序列化使用。 */
        final Type resultType;

        /** 本次调用的选项，包含序列化标识、超时、路由键与重试次数。 */
        final CallOptions options;

        /** 调用截止时间戳，单位为纳秒，越过后即使收到响应也按超时处理。 */
        final long deadline;

        /** 本次调用的遥测跨度，调用结束时以结果状态收尾。 */
        final RpcTelemetry.Call telemetry;

        /** 时间轮中的超时任务句柄，调用提前结束时取消，避免超时任务堆积。 */
        volatile Timeout timeout;

        Pending(long id, Endpoint endpoint, Type type, CallOptions options, long deadline) {
            this.id = id;
            this.endpoint = endpoint;
            channel = endpoint.channel;
            resultType = type;
            this.options = options;
            this.deadline = deadline;
            telemetry = RpcTelemetry.start("client", null);
        }
    }

    /** 使用默认生产参数、默认序列化器与轮询负载均衡创建客户端。 */
    public RpcClient() {
        this(RpcConfig.defaults());
    }

    /**
     * 使用指定调用超时创建客户端，其余参数取默认值。
     *
     * @param timeoutMillis 单次调用超时时间，单位为毫秒
     */
    public RpcClient(long timeoutMillis) {
        this(RpcConfig.builder().requestTimeoutMillis(Math.toIntExact(timeoutMillis)).build());
    }

    /**
     * 使用指定生产参数创建客户端，序列化器取默认注册表并以 JSON 为默认编码，不启用 TLS。
     *
     * @param config 生产参数
     */
    public RpcClient(RpcConfig config) {
        this(config, SerializerRegistry.defaults(), (byte) 1, null, RoundRobinLoadBalancer::new);
    }

    /**
     * 以少量关键参数创建客户端，其余参数取默认值，便于测试与快速验证。
     *
     * @param serializer 唯一启用的序列化器，同时作为默认编码
     * @param maxMessageLength 最大消息体字节数
     * @param timeoutMillis 单次调用超时时间，单位为毫秒
     */
    public RpcClient(MessageSerializer serializer, int maxMessageLength, long timeoutMillis) {
        this(
                RpcConfig.builder()
                        .maxMessageLength(maxMessageLength)
                        .requestTimeoutMillis(Math.toIntExact(timeoutMillis))
                        .build(),
                new SerializerRegistry(serializer),
                serializer.id(),
                null,
                RoundRobinLoadBalancer::new);
    }

    /**
     * 完整参数构造客户端：创建事件循环组、各线程池、时间轮、准入信号量与字节预算，并注册遥测指标。
     *
     * <p>构造阶段只分配资源，不建立任何连接；默认序列化标识会立即在注册表中校验，
     * 让配置错误在启动期暴露而不是首次调用时才失败。
     *
     * @param config 生产参数
     * @param serializers 序列化器注册表
     * @param defaultSerializer 默认序列化标识，必须已在注册表中
     * @param ssl TLS 上下文；为 {@code null} 时使用明文传输
     * @param balancerFactory 负载均衡器工厂，为每个服务创建独立实例
     */
    public RpcClient(
            RpcConfig config,
            SerializerRegistry serializers,
            byte defaultSerializer,
            SslContext ssl,
            java.util.function.Supplier<LoadBalancer> balancerFactory) {
        this.config = Objects.requireNonNull(config);
        this.serializers = Objects.requireNonNull(serializers);
        serializers.get(defaultSerializer);
        this.defaultSerializer = defaultSerializer;
        this.ssl = ssl;
        this.balancerFactory = Objects.requireNonNull(balancerFactory);
        loops =
                new MultiThreadIoEventLoopGroup(
                        config.clientIoThreads(),
                        RpcExecutors.factory("client-io"),
                        NioIoHandler.newFactory());
        codec =
                RpcExecutors.pool(
                        "client-codec", config.codecThreads(), config.codecQueueCapacity());
        completions =
                RpcExecutors.pool(
                        "completion", config.completionThreads(), config.maxPendingRequests());
        discoveryExecutor =
                Executors.newSingleThreadScheduledExecutor(RpcExecutors.factory("discovery"));
        retryExecutor = Executors.newSingleThreadScheduledExecutor(RpcExecutors.factory("retry"));
        timer = new HashedWheelTimer(RpcExecutors.factory("timer"), 10, TimeUnit.MILLISECONDS, 512);
        retryCompletions =
                RpcExecutors.pool(
                        "retry-completion",
                        config.completionThreads(),
                        config.maxPendingRequests());
        retrySlots = new Semaphore(config.maxPendingRequests());
        slots = new Semaphore(config.maxPendingRequests());
        budget = new ByteBudget(config.maxBufferedBytes());
        resources =
                RpcTelemetry.bind(
                        "client",
                        pending::size,
                        this::connectionCount,
                        () -> codec.getQueue().size() + completions.getQueue().size(),
                        budget::used);
    }

    /**
     * 直连指定地址建立一条连接，适用于不接入注册中心的场景。
     *
     * <p>直连端点在连接表中以通配服务名 {@code "*"} 登记，因此可被任意服务名的调用选中。
     * 该方法阻塞等待连接与 TLS 握手完成，因此禁止在框架线程上调用。
     *
     * @param host 目标主机
     * @param port 目标端口
     * @throws IllegalStateException 连接已存在、连接数超限或客户端已关闭时抛出
     * @throws RpcException 连接或握手失败、超时时抛出
     * @throws InterruptedException 等待期间被中断时抛出
     */
    public void connect(String host, int port) throws InterruptedException {
        RpcExecutors.requireExternalThread();
        Endpoint endpoint;
        synchronized (this) {
            ensureRunning();
            String key = "*/" + host + ":" + port;
            if (endpoints.containsKey(key)) {
                throw new IllegalStateException("连接已存在");
            }
            if (endpoints.size() >= config.maxClientConnections()) {
                throw new IllegalStateException("连接数超过上限");
            }
            endpoint = new Endpoint("*", new ServiceInstance(host + ":" + port, host, port, 1));
            endpoints.put(key, endpoint);
        }
        try {
            open(endpoint)
                    .get(
                            config.connectTimeoutMillis() + config.handshakeTimeoutMillis(),
                            TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw failure("连接失败", e);
        }
    }

    /**
     * 订阅注册中心中某个服务的实例列表，按实例变更自动增删连接。
     *
     * <p>实例快照与发现失败都被投递到单线程的发现执行器上串行处理，避免并发变更连接表；
     * 发现失败只记录首次失败时间，并不立即清空本地实例，从而在注册中心短暂不可用时仍能继续调用。
     *
     * @param service 服务名
     * @param discovery 注册中心适配器
     * @throws IllegalStateException 服务已订阅或客户端已关闭时抛出
     */
    public synchronized void subscribe(String service, ServiceDiscovery discovery) {
        ensureRunning();
        if (subscriptions.containsKey(service)) {
            throw new IllegalStateException("服务已订阅");
        }
        AutoCloseable subscription =
                discovery.subscribe(
                        service,
                        instances -> publish(() -> update(service, instances)),
                        error ->
                                publish(
                                        () -> {
                                            synchronized (this) {
                                                discoveryFailure.putIfAbsent(
                                                        service, System.nanoTime());
                                            }
                                        }));
        subscriptions.put(service, subscription);
    }

    /**
     * 把发现事件投递到单线程发现执行器串行处理。
     *
     * <p>停机后执行器已关闭，此时迟到的发现事件被静默丢弃，不影响停机流程。
     *
     * @param task 待执行的发现事件处理任务
     */
    private void publish(Runnable task) {
        try {
            discoveryExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
            /* 停机后忽略迟到发现事件。 */
        }
    }

    /**
     * 按最新实例快照调整连接表：摘除已下线实例，为新增实例建立连接。
     *
     * <p>权重为 0 的实例视为已摘除；被移除的端点先标记为 removed 使其立即退出选路，
     * 延迟到排空超时后才真正关闭连接，让该连接上的在途请求有机会正常收到响应。
     * 新增连接受最大连接数限制。收到有效快照即清除该服务的发现失败标记。
     *
     * @param service 服务名
     * @param instances 最新实例快照
     */
    private synchronized void update(String service, List<ServiceInstance> instances) {
        if (draining) {
            return;
        }
        discoveryFailure.remove(service);
        RpcTelemetry.event("discovery.update");
        Map<String, ServiceInstance> desired = new HashMap<>();
        for (ServiceInstance instance : instances) {
            if (instance.weight() > 0) {
                desired.put(service + "/" + instance.id(), instance);
            }
        }
        var iterator = endpoints.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Endpoint endpoint = entry.getValue();
            if (endpoint.service.equals(service)
                    && !endpoint.instance.equals(desired.get(entry.getKey()))) {
                endpoint.removed = true;
                iterator.remove();
                discoveryExecutor.schedule(
                        () -> {
                            if (endpoint.channel != null) {
                                endpoint.channel.close();
                            }
                        },
                        config.drainTimeoutMillis(),
                        TimeUnit.MILLISECONDS);
            }
        }
        desired.forEach(
                (key, instance) -> {
                    if (!endpoints.containsKey(key)
                            && endpoints.size() < config.maxClientConnections()) {
                        Endpoint endpoint = new Endpoint(service, instance);
                        endpoints.put(key, endpoint);
                        open(endpoint);
                    }
                });
    }

    /**
     * 为指定端点发起连接，并在 TLS 场景下等待握手完成。
     *
     * <p>正在停机、端点已摘除或已有连接进行中时直接以失败返回，避免重复连接同一实例。
     *
     * @param endpoint 目标端点
     * @return 连接结果；连接或握手失败时以异常完成
     */
    private synchronized CompletableFuture<Void> open(Endpoint endpoint) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (draining || endpoint.removed || endpoint.connecting) {
            result.completeExceptionally(failure("连接不可建立", null));
            return result;
        }
        endpoint.connecting = true;
        Bootstrap bootstrap =
                new Bootstrap()
                        .group(loops)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.TCP_NODELAY, true)
                        .option(ChannelOption.SO_KEEPALIVE, true)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                        .option(ChannelOption.WRITE_BUFFER_WATER_MARK, config.waterMark())
                        .option(ChannelOption.AUTO_READ, true)
                        .option(ChannelOption.ALLOW_HALF_CLOSURE, false)
                        .handler(
                                new ChannelInitializer<SocketChannel>() {
                                    @Override
                                    /**
                                     * 为新建立的连接安装 RPC 管线。
                                     *
                                     * @param channel 新建立的连接
                                     */
                                    protected void initChannel(SocketChannel channel) {
                                        RpcPipeline.install(
                                                channel,
                                                config,
                                                serializers,
                                                budget,
                                                ssl,
                                                endpoint.instance.host(),
                                                endpoint.instance.port(),
                                                new RpcClientHandler(RpcClient.this));
                                    }
                                });
        bootstrap
                .connect(endpoint.instance.host(), endpoint.instance.port())
                .addListener(
                        (ChannelFuture future) -> {
                            if (!future.isSuccess()) {
                                connected(endpoint, null, future.cause(), result);
                                return;
                            }
                            SslHandler tls = future.channel().pipeline().get(SslHandler.class);
                            if (tls == null) {
                                connected(endpoint, future.channel(), null, result);
                            } else {
                                tls.handshakeFuture()
                                        .addListener(
                                                handshake ->
                                                        connected(
                                                                endpoint,
                                                                future.channel(),
                                                                handshake.isSuccess()
                                                                        ? null
                                                                        : handshake.cause(),
                                                                result));
                            }
                        });
        return result;
    }

    /**
     * 处理连接与握手结果：成功则挂载连接并清零失败计数，失败则关闭连接并安排重连。
     *
     * @param endpoint 目标端点
     * @param channel 已建立的连接；连接失败时为 {@code null}
     * @param error 连接或握手异常；成功时为 {@code null}
     * @param result 待完成的连接结果
     */
    private synchronized void connected(
            Endpoint endpoint, Channel channel, Throwable error, CompletableFuture<Void> result) {
        endpoint.connecting = false;
        if (draining
                || endpoint.removed
                || error != null
                || channel == null
                || !channel.isActive()) {
            if (channel != null) {
                channel.close();
            }
            result.completeExceptionally(failure("连接或握手失败", error));
            reconnect(endpoint);
            return;
        }
        endpoint.channel = channel;
        endpoint.failures = 0;
        result.complete(null);
    }

    /**
     * 为失败的端点安排一次退避重连。
     *
     * <p>退避按失败次数指数增长（200ms 起、最多 6 次翻倍、上限 10 秒），并乘以 0.8~1.2 的随机抖动，
     * 避免大量连接在同一时刻集中重连造成对端瞬时压力。停机或端点已摘除时不再重连。
     *
     * @param endpoint 需要重连的端点
     */
    private synchronized void reconnect(Endpoint endpoint) {
        if (draining || endpoint.removed || endpoint.connecting) {
            return;
        }
        RpcTelemetry.event("reconnect");
        long delay = Math.min(10000, 200L << Math.min(endpoint.failures++, 6));
        delay = (long) (delay * ThreadLocalRandom.current().nextDouble(0.8, 1.2));
        try {
            discoveryExecutor.schedule(() -> open(endpoint), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            /* 停机时不再重连。 */
        }
    }

    /**
     * 为一次调用选择可用连接。
     *
     * <p>先做发现失败保护：某服务发现失败持续超过 30 秒即判定本地实例缓存已过期并直接失败，
     * 避免长期把请求发往可能已经下线的实例。随后只把"未摘除、连接活跃、通道可写、连接在途未满"
     * 的端点作为候选交给负载均衡器，因此均衡策略无需关心连接状态。
     *
     * @param service 服务名
     * @param key 路由键，供一致性哈希等策略使用
     * @return 被选中的端点
     * @throws RpcException 发现缓存过期时抛出
     * @throws IllegalStateException 没有可用候选时抛出
     */
    private synchronized Endpoint select(String service, String key) {
        Long failedSince = discoveryFailure.get(service);
        if (failedSince != null && System.nanoTime() - failedSince > TimeUnit.SECONDS.toNanos(30)) {
            throw failure("服务发现缓存已过期", null);
        }
        List<LoadBalancer.Candidate> candidates =
                endpoints.values().stream()
                        .filter(
                                e ->
                                        (e.service.equals(service) || e.service.equals("*"))
                                                && !e.removed
                                                && e.channel != null
                                                && e.channel.isActive()
                                                && e.channel.isWritable()
                                                && e.requests.availablePermits() > 0)
                        .map(e -> new LoadBalancer.Candidate(e.instance, e.channel))
                        .toList();
        if (candidates.isEmpty()) {
            throw failure("没有可用服务实例", null);
        }
        Channel selected =
                balancers
                        .computeIfAbsent(service, k -> balancerFactory.get())
                        .select(candidates, key)
                        .channel();
        return endpoints.values().stream()
                .filter(e -> e.channel == selected)
                .findFirst()
                .orElseThrow();
    }

    /**
     * 以默认选项发起一次异步调用：默认序列化器、配置中的请求超时、不重试。
     *
     * @param service 服务名
     * @param method 方法名
     * @param resultType 返回值类型
     * @param types 参数声明类型
     * @param arguments 参数值，与类型一一对应
     * @param <T> 返回值类型
     * @return 调用结果
     */
    public <T> CompletableFuture<T> invokeAsync(
            String service,
            String method,
            Class<T> resultType,
            Class<?>[] types,
            Object[] arguments) {
        return invokeAsync(
                service,
                method,
                (Type) resultType,
                types,
                arguments,
                new CallOptions(defaultSerializer, config.requestTimeoutMillis(), null, false, 0));
    }

    /**
     * 按指定选项发起一次异步调用，支持泛型返回值与重试。
     *
     * <p>不重试时直接走单次尝试。需要重试时额外占用一个重试准入许可，并由时间轮持有一个
     * 覆盖全部尝试的总预算超时：即使多次重试，调用方看到的总耗时也不会超过声明的超时。
     * 结果 future 只会被完成一次，超时、重试成功、取消与停机互相竞争时以第一个到达者为准；
     * 对外 future 在停机时通过终止回调立即失败，不会悬挂。
     *
     * @param service 服务名
     * @param method 方法名
     * @param resultType 返回值类型，可为参数化类型
     * @param types 参数声明类型
     * @param arguments 参数值，与类型一一对应
     * @param options 调用选项
     * @param <T> 返回值类型
     * @return 调用结果；重试容量已满时返回已失败的结果
     * @throws IllegalStateException 客户端已关闭时抛出
     */
    public <T> CompletableFuture<T> invokeAsync(
            String service,
            String method,
            Type resultType,
            Type[] types,
            Object[] arguments,
            CallOptions options) {
        if (options.retries() == 0) {
            return attempt(service, method, resultType, types, arguments, options);
        }
        if (!retrySlots.tryAcquire()) {
            return CompletableFuture.failedFuture(
                    new RpcException(RpcErrorCode.SERVER_BUSY, "重试调用容量已满"));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<T>> active = new AtomicReference<>();
        AtomicBoolean ended = new AtomicBoolean();
        java.util.function.BiConsumer<T, Throwable> complete =
                (value, error) -> {
                    if (!ended.compareAndSet(false, true)) {
                        return;
                    }
                    retryClosers.remove(result);
                    CompletableFuture<T> current = active.get();
                    if (current != null && !current.isDone()) {
                        current.cancel(false);
                    }
                    retryCompletions.execute(
                            () -> {
                                try {
                                    if (error == null) {
                                        result.complete(value);
                                    } else {
                                        result.completeExceptionally(error);
                                    }
                                } finally {
                                    retrySlots.release();
                                }
                            });
                };
        synchronized (this) {
            if (draining) {
                retrySlots.release();
                throw new IllegalStateException("客户端已关闭");
            }
            retryClosers.put(result, () -> complete.accept(null, failure("客户端停机", null)));
        }
        Timeout totalTimeout =
                timer.newTimeout(
                        ignored ->
                                complete.accept(
                                        null,
                                        new RpcException(RpcErrorCode.TIMEOUT, "RPC 总调用预算已耗尽")),
                        options.timeoutMillis(),
                        TimeUnit.MILLISECONDS);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.timeoutMillis());
        result.whenComplete(
                (value, error) -> {
                    totalTimeout.cancel();
                    if (result.isCancelled()) {
                        complete.accept(null, new CancellationException("调用已取消"));
                    }
                });
        retry(
                service,
                method,
                resultType,
                types.clone(),
                arguments.clone(),
                options,
                deadline,
                result,
                active,
                complete,
                0);
        return result;
    }

    /**
     * 执行一次带重试语义的尝试，必要时安排下一次重试。
     *
     * <p>每次尝试的超时取"总预算剩余时间"，因此重试不会突破调用方声明的总超时；
     * 只有连接关闭类错误才重试（这类失败通常是对端实例下线，换实例后可成功），
     * 业务异常与超时不重试以避免重复副作用。重试前等待 20~50ms 随机退避，
     * 且退避在独立执行器中完成，不占用 I/O、时间轮或发现线程。
     *
     * @param service 服务名
     * @param method 方法名
     * @param type 返回值类型
     * @param types 参数声明类型
     * @param arguments 参数值
     * @param options 原始调用选项，其中的重试次数决定还能重试几次
     * @param deadline 总预算截止的纳秒时间戳
     * @param result 对外返回的结果
     * @param active 当前正在进行的尝试，用于在整体结束时取消
     * @param complete 只会生效一次的终态回调
     * @param attemptNumber 当前尝试序号，从 0 开始
     * @param <T> 返回值类型
     */
    private <T> void retry(
            String service,
            String method,
            Type type,
            Type[] types,
            Object[] arguments,
            CallOptions options,
            long deadline,
            CompletableFuture<T> result,
            AtomicReference<CompletableFuture<T>> active,
            java.util.function.BiConsumer<T, Throwable> complete,
            int attemptNumber) {
        if (result.isDone()) {
            return;
        }
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) {
            complete.accept(null, new RpcException(RpcErrorCode.TIMEOUT, "重试总预算已耗尽"));
            return;
        }
        CompletableFuture<T> current;
        try {
            current =
                    attempt(
                            service,
                            method,
                            type,
                            types,
                            arguments,
                            new CallOptions(
                                    options.serializerId(),
                                    remaining,
                                    options.routingKey(),
                                    true,
                                    0));
        } catch (RuntimeException error) {
            current = CompletableFuture.failedFuture(error);
        }
        active.set(current);
        if (result.isCancelled()) {
            current.cancel(false);
            return;
        }
        current.whenComplete(
                (value, error) -> {
                    boolean retryable =
                            error instanceof RpcException rpc
                                    && rpc.getErrorCode() == RpcErrorCode.CONNECTION_CLOSED;
                    if (retryable && attemptNumber < options.retries() && !result.isDone()) {
                        try {
                            // 退避在独立调用者任务中等待，避免阻塞 I/O、时间轮或发现事件处理。
                            retryExecutor.schedule(
                                    () ->
                                            retry(
                                                    service,
                                                    method,
                                                    type,
                                                    types,
                                                    arguments,
                                                    options,
                                                    deadline,
                                                    result,
                                                    active,
                                                    complete,
                                                    attemptNumber + 1),
                                    ThreadLocalRandom.current().nextLong(20, 51),
                                    TimeUnit.MILLISECONDS);
                        } catch (RuntimeException rejected) {
                            complete.accept(null, rejected);
                        }
                    } else {
                        complete.accept(value, error);
                    }
                });
    }

    /**
     * 执行单次调用尝试：选连接、过准入、登记在途请求、挂超时、提交编码任务。
     *
     * <p>准入顺序为客户端全局在途许可、连接级在途许可，任一不通过即以
     * {@link RpcErrorCode#SERVER_BUSY} 快速失败，并回退已获取的许可。
     * 请求编号单调递增且不回绕，耗尽即拒绝新调用，避免编号复用导致响应错配。
     * 在途登记、超时挂载都在同一把锁内完成，且挂载后再次检查在途表，
     * 防止调用已经结束却留下一个悬空的超时任务。
     *
     * @param service 服务名
     * @param method 方法名
     * @param resultType 返回值类型
     * @param types 参数声明类型
     * @param arguments 参数值
     * @param options 调用选项
     * @param <T> 返回值类型
     * @return 调用结果
     * @throws IllegalArgumentException 参数数量与类型数量不一致时抛出
     * @throws IllegalStateException 客户端已关闭或请求编号耗尽时抛出
     */
    private <T> CompletableFuture<T> attempt(
            String service,
            String method,
            Type resultType,
            Type[] types,
            Object[] arguments,
            CallOptions options) {
        Objects.requireNonNull(resultType);
        Objects.requireNonNull(types);
        Objects.requireNonNull(arguments);
        if (types.length != arguments.length) {
            throw new IllegalArgumentException("参数数量不匹配");
        }
        Pending call;
        synchronized (this) {
            ensureRunning();
            serializers.get(options.serializerId());
            Endpoint endpoint = select(service, options.routingKey());
            if (!slots.tryAcquire()) {
                return CompletableFuture.failedFuture(
                        new RpcException(RpcErrorCode.SERVER_BUSY, "客户端在途请求已满"));
            }
            if (!endpoint.requests.tryAcquire()) {
                slots.release();
                return CompletableFuture.failedFuture(
                        new RpcException(RpcErrorCode.SERVER_BUSY, "连接在途请求已满"));
            }
            long id = ids.updateAndGet(value -> value == Long.MAX_VALUE ? value : value + 1);
            if (id == Long.MAX_VALUE) {
                slots.release();
                endpoint.requests.release();
                throw new IllegalStateException("请求编号已耗尽");
            }
            call =
                    new Pending(
                            id,
                            endpoint,
                            resultType,
                            options,
                            System.nanoTime()
                                    + TimeUnit.MILLISECONDS.toNanos(options.timeoutMillis()));
            pending.put(id, call);
            call.future.whenComplete(
                    (value, error) -> {
                        if (call.future.isCancelled()) {
                            finish(call, null, new CancellationException("调用已取消"));
                        }
                    });
            call.timeout =
                    timer.newTimeout(
                            ignored ->
                                    finish(
                                            call,
                                            null,
                                            new RpcException(RpcErrorCode.TIMEOUT, "RPC 调用超时")),
                            options.timeoutMillis(),
                            TimeUnit.MILLISECONDS);
            if (!pending.containsKey(id)) {
                call.timeout.cancel();
            }
        }
        Type[] parameterTypes = types.clone();
        Object[] values = arguments.clone();
        try {
            codec.execute(() -> send(call, service, method, parameterTypes, values));
        } catch (RejectedExecutionException error) {
            finish(call, null, new RpcException(RpcErrorCode.SERVER_BUSY, "编码队列已满", error));
        }
        @SuppressWarnings("unchecked")
        CompletableFuture<T> result = (CompletableFuture<T>) (CompletableFuture<?>) call.future;
        return result;
    }

    /**
     * 在编码线程内序列化请求并提交写入。
     *
     * <p>请求中携带的超时是"剩余时间"而非原始超时，使服务端能判断请求是否已在排队中过期。
     * 编码完成后若调用已结束或已超时则直接放弃发送，避免无效流量。
     * 报文字节在提交事件循环前先申请预算、写出后归还，覆盖跨线程等待窗口。
     * 真正写出前在锁内再次确认调用仍在途且未超时，避免向已放弃的调用发送请求。
     *
     * @param call 在途调用
     * @param service 服务名
     * @param method 方法名
     * @param types 参数声明类型
     * @param values 参数值
     */
    private void send(Pending call, String service, String method, Type[] types, Object[] values) {
        try {
            MessageSerializer serializer = serializers.get(call.options.serializerId());
            serializer.validateType(call.resultType);
            for (Type type : types) {
                serializer.validateType(type);
            }
            List<RpcPayload> payloads = new ArrayList<>();
            for (int i = 0; i < types.length; i++) {
                payloads.add(RpcPayload.of(values[i], types[i], serializer));
            }
            long remaining = TimeUnit.NANOSECONDS.toMillis(call.deadline - System.nanoTime());
            if (remaining <= 0 || !pending.containsKey(call.id)) {
                return;
            }
            RpcRequest request =
                    new RpcRequest(
                            service,
                            method,
                            Arrays.stream(types)
                                    .map(
                                            type ->
                                                    type instanceof Class<?> cls
                                                            ? cls.getName()
                                                            : ((java.lang.reflect.ParameterizedType)
                                                                            type)
                                                                    .getRawType()
                                                                    .getTypeName())
                                    .toList(),
                            payloads,
                            remaining,
                            call.telemetry.carrier());
            byte[] body = serializer.serialize(request);
            if (body.length > config.maxMessageLength()) {
                throw new RpcException(RpcErrorCode.SERIALIZATION_FAILED, "请求消息体超过上限");
            }
            if (!budget.acquire(body.length)) {
                finish(call, null, new RpcException(RpcErrorCode.SERVER_BUSY, "待写字节预算已满"));
                return;
            }
            try {
                call.channel
                        .eventLoop()
                        .execute(
                                () -> {
                                    try {
                                        synchronized (RpcClient.this) {
                                            if (!pending.containsKey(call.id)
                                                    || System.nanoTime() >= call.deadline) {
                                                return;
                                            }
                                            call.channel
                                                    .writeAndFlush(
                                                            new RpcFrame(
                                                                    RpcProtocol.REQUEST,
                                                                    serializer.id(),
                                                                    call.id,
                                                                    body))
                                                    .addListener(
                                                            future -> {
                                                                if (!future.isSuccess()) {
                                                                    finish(
                                                                            call,
                                                                            null,
                                                                            failure(
                                                                                    "请求发送失败",
                                                                                    future
                                                                                            .cause()));
                                                                    call.channel.close();
                                                                }
                                                            });
                                        }
                                    } finally {
                                        budget.release(body.length);
                                    }
                                });
            } catch (RuntimeException error) {
                budget.release(body.length);
                throw error;
            }
        } catch (RuntimeException error) {
            finish(call, null, error);
        }
    }

    /**
     * 接收响应帧并交由解码线程池匹配在途调用。
     *
     * <p>校验帧类型、来源连接与序列化标识必须与登记的在途调用一致，不一致说明协议或连接状态异常，
     * 直接关闭连接。找不到对应在途调用（已超时或已取消）时只归还字节预算，属于正常情况。
     * 解码任务用可丢弃任务包装，保证被拒绝或停机丢弃时字节预算仍会归还。
     *
     * @param channel 收到响应的连接
     * @param frame 响应帧
     */
    void receive(Channel channel, RpcFrame frame) {
        Pending call = pending.get(frame.requestId());
        if (frame.messageType() != RpcProtocol.RESPONSE
                || call != null
                        && (call.channel != channel
                                || frame.serializerId() != call.options.serializerId())) {
            RpcPipeline.consumed(budget, frame);
            channel.close();
            return;
        }
        if (call == null) {
            RpcPipeline.consumed(budget, frame);
            return;
        }
        RpcExecutors.Task task =
                new RpcExecutors.Task(
                        () -> {
                            try {
                                MessageSerializer serializer =
                                        serializers.get(frame.serializerId());
                                RpcResponse response =
                                        serializer.deserialize(frame.body(), RpcResponse.class);
                                if (response.success()) {
                                    finish(
                                            call,
                                            response.result().decode(call.resultType, serializer),
                                            null);
                                } else {
                                    finish(
                                            call,
                                            null,
                                            new RpcException(
                                                    response.error().code(),
                                                    response.error().message()));
                                }
                            } catch (RuntimeException error) {
                                finish(call, null, error);
                                channel.close();
                            }
                        },
                        () -> RpcPipeline.consumed(budget, frame));
        try {
            codec.execute(task);
        } catch (RejectedExecutionException error) {
            task.discard();
            finish(call, null, new RpcException(RpcErrorCode.SERVER_BUSY, "响应解码队列已满", error));
        }
    }

    /**
     * 结束一次在途调用：摘除登记、取消超时、归还许可，并在完成线程池中回调结果。
     *
     * <p>以"从在途表中原子摘除"作为唯一入口保证幂等：响应、超时、取消、断连、停机可能并发到达，
     * 只有第一个摘除成功者会完成结果。即使成功收到响应，只要已越过截止时间也按超时处理，
     * 保证调用方观察到的超时语义严格。用户回调在完成线程池执行，不阻塞 I/O 或解码线程。
     *
     * @param call 在途调用
     * @param value 返回值
     * @param error 失败原因；成功时为 {@code null}
     */
    private synchronized void finish(Pending call, Object value, Throwable error) {
        if (!pending.remove(call.id, call)) {
            return;
        }
        if (call.timeout != null) {
            call.timeout.cancel();
        }
        call.endpoint.requests.release();
        Throwable outcome =
                error == null && System.nanoTime() >= call.deadline
                        ? new RpcException(RpcErrorCode.TIMEOUT, "RPC 调用超时")
                        : error;
        completions.execute(
                () -> {
                    try (var scope = call.telemetry.context().makeCurrent()) {
                        call.telemetry.finish(
                                outcome == null
                                        ? "success"
                                        : outcome instanceof RpcException rpc
                                                ? rpc.getErrorCode().name()
                                                : "failure");
                        if (outcome == null) {
                            call.future.complete(value);
                        } else {
                            call.future.completeExceptionally(outcome);
                        }
                    } finally {
                        slots.release();
                    }
                });
    }

    /**
     * 处理连接断开：使该连接上的在途调用失败，摘除连接并安排重连。
     *
     * @param channel 已断开的连接
     */
    void disconnected(Channel channel) {
        // 断连事件要排在该连接已经提交的响应解码任务之后：对端优雅停机时，最后一批响应
        // 可能已经收到但还没解码完，直接判定连接关闭会让本已成功的调用变成失败。
        Runnable terminate =
                () ->
                        pending.values()
                                .forEach(
                                        call -> {
                                            if (call.channel == channel) {
                                                finish(call, null, failure("RPC 连接已关闭", null));
                                            }
                                        });
        try {
            codec.execute(terminate);
        } catch (RejectedExecutionException rejected) {
            terminate.run();
        }
        synchronized (this) {
            endpoints.values().stream()
                    .filter(e -> e.channel == channel)
                    .forEach(
                            e -> {
                                e.channel = null;
                                reconnect(e);
                            });
        }
    }

    /**
     * 发起一次同步调用，阻塞等待结果。
     *
     * <p>等待被中断时取消调用并恢复中断标记；执行异常会解包，业务侧看到的仍是原始
     * {@link RpcException}，便于按错误类型判断。
     *
     * @param service 服务名
     * @param method 方法名
     * @param resultType 返回值类型
     * @param types 参数声明类型
     * @param arguments 参数值
     * @param <T> 返回值类型
     * @return 调用返回值
     * @throws IllegalStateException 在框架线程上调用时抛出，避免阻塞等待自身造成死锁
     * @throws RpcException 调用失败或等待被中断时抛出
     */
    public <T> T invoke(
            String service,
            String method,
            Class<T> resultType,
            Class<?>[] types,
            Object[] arguments) {
        RpcExecutors.requireExternalThread();
        CompletableFuture<T> future = invokeAsync(service, method, resultType, types, arguments);
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw failure("调用等待中断", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RpcException rpc) {
                throw rpc;
            }
            throw failure("RPC 调用失败", e.getCause());
        }
    }

    /**
     * 断言客户端尚未进入停机流程。
     *
     * @throws IllegalStateException 客户端已关闭时抛出
     */
    private void ensureRunning() {
        if (draining) {
            throw new IllegalStateException("客户端已关闭");
        }
    }

    /**
     * 构造连接类失败异常。
     *
     * <p>统一使用 {@link RpcErrorCode#CONNECTION_CLOSED}：该错误类型是重试判定的依据，
     * 表示换一个实例重试有意义。
     *
     * @param text 中文错误说明
     * @param error 原始异常，可为 {@code null}
     * @return RPC 异常
     */
    private static RpcException failure(String text, Throwable error) {
        return new RpcException(RpcErrorCode.CONNECTION_CLOSED, text, error);
    }

    /**
     * 统计当前活跃连接数，供遥测指标读取。
     *
     * @return 通道非空且处于活跃状态的连接数量
     */
    private synchronized int connectionCount() {
        return (int)
                endpoints.values().stream()
                        .filter(e -> e.channel != null && e.channel.isActive())
                        .count();
    }

    /**
     * 返回当前在途请求数，便于排查请求积压。
     *
     * @return 在途请求数
     */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * 返回本客户端使用的生产参数。
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
     * 异步关闭客户端，按"停止接入 → 排空在途 → 释放资源"的顺序收尾。
     *
     * <p>关闭在独立线程执行，步骤如下：置排空标记拒绝新调用；退订注册中心（订阅关闭失败不阻断流程）；
     * 停止发现与重连调度；在排空超时内等待在途调用与在途重试自然结束；超时后主动让残留调用失败，
     * 保证调用方的 future 不会悬挂；随后关闭连接、停止时间轮与各线程池、关闭事件循环组，
     * 最后注销遥测资源。
     *
     * <p>排空与关闭分别受 {@code drainTimeoutMillis} 与 {@code shutdownTimeoutMillis} 约束，
     * 保证停机时间有上界。重复调用返回同一个结果。
     *
     * @return 关闭完成的结果；关闭过程中出现异常时以异常完成
     */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing;
        }
        draining = true;
        closing = new CompletableFuture<>();
        List<Endpoint> closingEndpoints = List.copyOf(endpoints.values());
        RpcExecutors.factory("client-close")
                .newThread(
                        () -> {
                            try {
                                subscriptions
                                        .values()
                                        .forEach(
                                                subscription -> {
                                                    try {
                                                        subscription.close();
                                                    } catch (Exception ignored) {
                                                        /* 订阅关闭失败不能阻止本地资源回收。 */
                                                    }
                                                });
                                discoveryExecutor.shutdownNow();
                                retryExecutor.shutdown();
                                long deadline =
                                        System.nanoTime()
                                                + TimeUnit.MILLISECONDS.toNanos(
                                                        config.drainTimeoutMillis());
                                while ((!pending.isEmpty() || !retryClosers.isEmpty())
                                        && System.nanoTime() < deadline) {
                                    Thread.sleep(5);
                                }
                                retryClosers.values().forEach(Runnable::run);
                                pending.values()
                                        .forEach(
                                                call -> finish(call, null, failure("客户端停机", null)));
                                closingEndpoints.forEach(
                                        e -> {
                                            if (e.channel != null) {
                                                e.channel.close();
                                            }
                                        });
                                long closeDeadline =
                                        System.nanoTime()
                                                + TimeUnit.MILLISECONDS.toNanos(
                                                        config.shutdownTimeoutMillis());
                                timer.stop();
                                codec.shutdown();
                                completions.shutdown();
                                retryCompletions.shutdown();
                                codec.awaitTermination(
                                        remaining(closeDeadline), TimeUnit.MILLISECONDS);
                                completions.awaitTermination(
                                        remaining(closeDeadline), TimeUnit.MILLISECONDS);
                                retryCompletions.awaitTermination(
                                        remaining(closeDeadline), TimeUnit.MILLISECONDS);
                                RpcExecutors.stop(codec);
                                retryExecutor.shutdownNow();
                                loops.shutdownGracefully(
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
     * 同步关闭客户端，阻塞直到关闭流程结束。
     *
     * @throws IllegalStateException 在框架线程上调用时抛出，避免阻塞等待自身造成死锁
     */
    @Override
    public void close() {
        RpcExecutors.requireExternalThread();
        closeAsync().join();
    }
}
