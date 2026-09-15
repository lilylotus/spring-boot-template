package com.example.template.rpc.client;

import com.example.template.rpc.RpcException;
import com.example.template.rpc.RpcThreadFactory;
import com.example.template.rpc.discovery.ServiceDiscovery;
import com.example.template.rpc.discovery.StaticAddressServiceDiscovery;
import com.example.template.rpc.loadbalancer.LoadBalancer;
import com.example.template.rpc.loadbalancer.RoundRobinLoadBalancer;
import com.example.template.rpc.protocol.JacksonRpcSerializer;
import com.example.template.rpc.protocol.RpcMessage;
import com.example.template.rpc.protocol.RpcMessageDecoder;
import com.example.template.rpc.protocol.RpcMessageEncoder;
import com.example.template.rpc.protocol.RpcRequest;
import com.example.template.rpc.protocol.RpcResponse;
import com.example.template.rpc.protocol.RpcSerializerRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * RPC 客户端的异步核心：requestId + {@link CompletableFuture} + 全局映射表是整个框架异步能力的
 * 地基，配合 {@link HashedWheelTimer} 做请求超时检测。IO 线程数在构造时显式指定，不使用 Netty
 * 无参默认构造(会按 CPU 核心数创建过多线程)。
 */
public class NettyRpcClient implements AsyncRpcCall {

    private static final Logger log = LoggerFactory.getLogger(NettyRpcClient.class);

    /** 单条请求默认等待响应的最长时间。 */
    private final long requestTimeoutMillis;

    /** 客户端向服务端发送心跳包的间隔；服务端配合 IdleStateHandler 检测连接是否存活。 */
    private final long heartbeatIntervalSeconds;

    private final EventLoopGroup ioGroup;
    private final Bootstrap bootstrap;
    private final ServiceDiscovery serviceDiscovery;
    private final LoadBalancer loadBalancer;
    private final RpcSerializerRegistry serializerRegistry;
    private final RpcRequestIdGenerator requestIdGenerator = new RpcRequestIdGenerator();

    /** requestId -> 等待中的Future 全局映射表，是请求-响应异步匹配的核心数据结构。 */
    private final Map<Long, CompletableFuture<Object>> pendingRequests = new ConcurrentHashMap<>();

    /** 专门用于超时检测的时间轮，性能远好于给每个请求都创建一个独立的定时任务。 */
    private final Timer timeoutTimer;

    private final ScheduledExecutorService heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(new RpcThreadFactory("rpc-client-heartbeat", true));

    /**
     * 构造客户端。
     *
     * @param ioThreads                客户端 Netty IO 线程数，显式指定，不使用默认构造
     * @param requestTimeoutMillis     单条请求默认超时时间(毫秒)
     * @param heartbeatIntervalSeconds 心跳发送间隔(秒)
     */
    public NettyRpcClient(int ioThreads, long requestTimeoutMillis, long heartbeatIntervalSeconds) {
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.serializerRegistry = new RpcSerializerRegistry();
        this.loadBalancer = new RoundRobinLoadBalancer();
        this.timeoutTimer = new HashedWheelTimer(new RpcThreadFactory("rpc-client-timeout-timer", true));

        // 客户端IO线程只做编解码和读写转发，同样显式指定线程数，不依赖CPU核心数推导出的默认值
        this.ioGroup = new NioEventLoopGroup(ioThreads, new RpcThreadFactory("rpc-client-io", true));
        this.bootstrap = new Bootstrap()
            .group(ioGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        .addLast(new RpcMessageDecoder(serializerRegistry))
                        .addLast(new RpcMessageEncoder(serializerRegistry))
                        .addLast(new RpcClientHandler(NettyRpcClient.this));
                }
            });
        this.serviceDiscovery = new StaticAddressServiceDiscovery(bootstrap);
    }

    /**
     * 供单元测试直接注入 {@link ServiceDiscovery}/{@link LoadBalancer} 的构造函数，跳过真实的
     * Netty Bootstrap/IO 线程组构建，专注验证 requestId -&gt; Future 映射、超时竞态等核心逻辑。
     * 通过该构造函数创建的实例 {@code ioGroup}/{@code bootstrap} 均为 {@code null}，
     * {@link #shutdown()} 中已做空值保护，不会调用 {@link #start} 之外的网络相关方法。
     */
    NettyRpcClient(ServiceDiscovery serviceDiscovery, LoadBalancer loadBalancer, long requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.heartbeatIntervalSeconds = 0;
        this.serializerRegistry = new RpcSerializerRegistry();
        this.loadBalancer = loadBalancer;
        this.timeoutTimer = new HashedWheelTimer(new RpcThreadFactory("rpc-client-timeout-timer-test", true));
        this.ioGroup = null;
        this.bootstrap = null;
        this.serviceDiscovery = serviceDiscovery;
    }

    /**
     * 启动客户端：与初始地址列表建立连接，并开始定时发送心跳。
     *
     * @param addresses 服务端地址列表
     */
    public void start(List<InetSocketAddress> addresses) {
        serviceDiscovery.start(addresses);
        heartbeatScheduler.scheduleAtFixedRate(
            this::sendHeartbeatToAllChannels, heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * 发送一次请求并返回其 Future。核心的异步能力：注册超时任务，经负载均衡选择连接后写出请求，
     * 写入失败立即让 Future 失败，不用等超时时间耗尽。
     *
     * @param request 请求消息体
     * @return 服务端响应到达(或超时/写失败)后完成的 Future
     */
    public CompletableFuture<Object> sendRequest(RpcRequest request) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        pendingRequests.put(request.getRequestId(), future);

        Timeout timeout = timeoutTimer.newTimeout(t -> {
            // 超时任务触发时，只有真正从map里remove到非null值的一方才能complete这个Future，
            // 与响应到达时的remove()操作形成原子竞争，避免同一个Future被重复complete
            CompletableFuture<Object> pending = pendingRequests.remove(request.getRequestId());
            if (pending != null) {
                pending.completeExceptionally(
                    new RpcException("请求超时 requestId=" + request.getRequestId()));
            }
        }, requestTimeoutMillis, TimeUnit.MILLISECONDS);

        try {
            Channel channel = loadBalancer.select(serviceDiscovery.getChannels(), request);
            RpcMessage message = RpcMessage.request(JacksonRpcSerializer.TYPE_CODE, request);
            channel.writeAndFlush(message).addListener(f -> {
                if (!f.isSuccess()) {
                    CompletableFuture<Object> pending = pendingRequests.remove(request.getRequestId());
                    if (pending != null) {
                        timeout.cancel();
                        pending.completeExceptionally(f.cause());
                    }
                }
            });
        } catch (RpcException e) {
            // 选连接阶段就失败(比如没有可用连接)，同样不需要等超时，立即让调用方拿到失败结果
            CompletableFuture<Object> pending = pendingRequests.remove(request.getRequestId());
            if (pending != null) {
                timeout.cancel();
                pending.completeExceptionally(e);
            }
        }

        return future;
    }

    /**
     * 生成下一个进程内唯一的requestId，供 {@link RpcClientProxy} 组装请求时使用。
     *
     * @return 下一个requestId
     */
    public long nextRequestId() {
        return requestIdGenerator.next();
    }

    @Override
    public CompletableFuture<Object> callAsync(
        String interfaceName, String methodName, String[] parameterTypes, Object[] parameters) {
        RpcRequest request = new RpcRequest(
            requestIdGenerator.next(), interfaceName, methodName, parameterTypes, parameters);
        return sendRequest(request);
    }

    /**
     * 客户端Handler收到服务端响应时调用，完成对应的Future。
     * <p>
     * {@code pendingRequests.remove()} 是原子操作，只有第一个成功remove到非null值的一方(正常响应
     * 或超时任务)才能真正complete这个Future；另一方拿到null，说明已经被处理过，直接跳过，
     * 不会出现"Future被重复complete"的问题。
     *
     * @param response 服务端响应
     */
    public void handleResponse(RpcResponse response) {
        CompletableFuture<Object> future = pendingRequests.remove(response.getRequestId());
        if (future == null) {
            // 说明该请求已经超时被移除了，响应来迟了，直接丢弃，不需要额外处理
            log.debug("收到requestId={}的响应，但对应请求已超时或已完成，丢弃", response.getRequestId());
            return;
        }
        if (response.isSuccess()) {
            future.complete(response.getResult());
        } else {
            future.completeExceptionally(new RpcException(response.getErrorMessage()));
        }
    }

    private void sendHeartbeatToAllChannels() {
        for (Channel channel : serviceDiscovery.getChannels()) {
            if (channel.isActive()) {
                channel.writeAndFlush(RpcMessage.heartbeat(requestIdGenerator.next(), JacksonRpcSerializer.TYPE_CODE));
            }
        }
    }

    /**
     * 关闭客户端：停止心跳、断开所有连接、释放超时时间轮和IO线程组。
     */
    public void shutdown() {
        heartbeatScheduler.shutdownNow();
        serviceDiscovery.shutdown();
        timeoutTimer.stop();
        if (ioGroup != null) {
            ioGroup.shutdownGracefully();
        }
    }

}
