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
    public static final long DEFAULT_TIMEOUT_MILLIS = 5000;
    private final RpcConfig config;
    private final SerializerRegistry serializers;
    private final byte defaultSerializer;
    private final SslContext ssl;
    private final EventLoopGroup loops;
    private final ThreadPoolExecutor codec;
    private final ThreadPoolExecutor completions;
    private final ScheduledExecutorService discoveryExecutor;
    private final ScheduledExecutorService retryExecutor;
    private final ThreadPoolExecutor retryCompletions;
    private final Semaphore retrySlots;
    private final ConcurrentMap<CompletableFuture<?>, Runnable> retryClosers =
            new ConcurrentHashMap<>();
    private final HashedWheelTimer timer;
    private final ByteBudget budget;
    private final Semaphore slots;
    private final RpcTelemetry.Resources resources;
    private final ConcurrentMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Map<String, Endpoint> endpoints = new HashMap<>();
    private final Map<String, AutoCloseable> subscriptions = new HashMap<>();
    private final Map<String, Long> discoveryFailure = new HashMap<>();
    private final Map<String, LoadBalancer> balancers = new HashMap<>();
    private final java.util.function.Supplier<LoadBalancer> balancerFactory;
    private boolean draining;
    private CompletableFuture<Void> closing;

    private final class Endpoint {
        final String service;
        final ServiceInstance instance;
        final Semaphore requests = new Semaphore(config.maxRequestsPerConnection());
        volatile Channel channel;
        volatile boolean removed;
        boolean connecting;
        int failures;

        Endpoint(String service, ServiceInstance instance) {
            this.service = service;
            this.instance = instance;
        }
    }

    private final class Pending {
        final long id;
        final CompletableFuture<Object> future = new CompletableFuture<>();
        final Endpoint endpoint;
        final Channel channel;
        final Type resultType;
        final CallOptions options;
        final long deadline;
        final RpcTelemetry.Call telemetry;
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

    public RpcClient() {
        this(RpcConfig.defaults());
    }

    public RpcClient(long timeoutMillis) {
        this(RpcConfig.builder().requestTimeoutMillis(Math.toIntExact(timeoutMillis)).build());
    }

    public RpcClient(RpcConfig config) {
        this(config, SerializerRegistry.defaults(), (byte) 1, null, RoundRobinLoadBalancer::new);
    }

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

    private void publish(Runnable task) {
        try {
            discoveryExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
            /* 停机后忽略迟到发现事件。 */
        }
    }

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

    void receive(Channel channel, RpcFrame frame) {
        Pending call = pending.get(frame.requestId());
        if (frame.messageType() != RpcProtocol.RESPONSE
                || call != null
                        && (call.channel != channel
                                || frame.serializerId() != call.options.serializerId())) {
            RpcPipeline.consumed(channel, frame);
            channel.close();
            return;
        }
        if (call == null) {
            RpcPipeline.consumed(channel, frame);
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
                        () -> RpcPipeline.consumed(channel, frame));
        try {
            codec.execute(task);
        } catch (RejectedExecutionException error) {
            task.discard();
            finish(call, null, new RpcException(RpcErrorCode.SERVER_BUSY, "响应解码队列已满", error));
        }
    }

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

    void disconnected(Channel channel) {
        pending.values()
                .forEach(
                        call -> {
                            if (call.channel == channel) {
                                finish(call, null, failure("RPC 连接已关闭", null));
                            }
                        });
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

    private void ensureRunning() {
        if (draining) {
            throw new IllegalStateException("客户端已关闭");
        }
    }

    private static RpcException failure(String text, Throwable error) {
        return new RpcException(RpcErrorCode.CONNECTION_CLOSED, text, error);
    }

    private synchronized int connectionCount() {
        return (int)
                endpoints.values().stream()
                        .filter(e -> e.channel != null && e.channel.isActive())
                        .count();
    }

    public int pendingCount() {
        return pending.size();
    }

    public RpcConfig config() {
        return config;
    }

    public long bufferedBytes() {
        return budget.used();
    }

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

    private static long remaining(long deadline) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    @Override
    public void close() {
        RpcExecutors.requireExternalThread();
        closeAsync().join();
    }
}
