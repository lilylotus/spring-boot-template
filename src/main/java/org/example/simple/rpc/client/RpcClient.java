package org.example.simple.rpc.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Promise;
import tools.jackson.databind.JsonNode;

import org.example.simple.rpc.common.JacksonJsonSerializer;
import org.example.simple.rpc.common.MessageSerializer;
import org.example.simple.rpc.common.RpcError;
import org.example.simple.rpc.common.RpcErrorCode;
import org.example.simple.rpc.common.RpcException;
import org.example.simple.rpc.common.RpcMessageDecoder;
import org.example.simple.rpc.common.RpcMessageEncoder;
import org.example.simple.rpc.common.RpcProtocol;
import org.example.simple.rpc.common.RpcRequest;
import org.example.simple.rpc.common.RpcResponse;

/**
 * 维护单个 Netty 连接并提供同步 JSON RPC 调用。
 */
public final class RpcClient implements AutoCloseable {

    /** 默认调用超时为五秒。 */
    public static final long DEFAULT_TIMEOUT_MILLIS = 5000L;

    private final MessageSerializer serializer;
    private final int maxMessageLength;
    private final long timeoutMillis;
    private final EventLoopGroup eventLoopGroup;
    private final DefaultEventLoop responseEventLoop;
    private final ConcurrentMap<String, Promise<RpcResponse>> pendingCalls = new ConcurrentHashMap<>();
    private final RpcClientHandler responseHandler;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Channel channel;

    /**
     * 使用默认 JSON 序列化和超时配置创建客户端。
     */
    public RpcClient() {
        this(new JacksonJsonSerializer(), RpcProtocol.DEFAULT_MAX_MESSAGE_LENGTH, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * 使用指定调用超时创建客户端。
     *
     * @param timeoutMillis 调用超时毫秒数
     */
    public RpcClient(long timeoutMillis) {
        this(new JacksonJsonSerializer(), RpcProtocol.DEFAULT_MAX_MESSAGE_LENGTH, timeoutMillis);
    }

    /**
     * 使用完整配置创建客户端。
     *
     * @param serializer 消息序列化器
     * @param maxMessageLength 最大消息体字节数
     * @param timeoutMillis 调用超时毫秒数
     */
    public RpcClient(MessageSerializer serializer, int maxMessageLength, long timeoutMillis) {
        this(serializer, maxMessageLength, timeoutMillis, new DefaultEventLoop());
    }

    RpcClient(
        MessageSerializer serializer,
        int maxMessageLength,
        long timeoutMillis,
        DefaultEventLoop responseEventLoop) {
        this.serializer = Objects.requireNonNull(serializer, "消息序列化器不能为空");
        if (maxMessageLength <= 0 || timeoutMillis <= 0) {
            throw new IllegalArgumentException("最大消息长度和调用超时必须大于零");
        }
        this.maxMessageLength = maxMessageLength;
        this.timeoutMillis = timeoutMillis;
        this.responseEventLoop = Objects.requireNonNull(responseEventLoop, "响应事件循环不能为空");
        this.eventLoopGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.responseHandler = new RpcClientHandler(pendingCalls, responseEventLoop);
    }

    /**
     * 连接指定 RPC 服务端。
     *
     * @param host 服务端主机
     * @param port 服务端端口
     * @throws InterruptedException 当前线程等待连接时被中断
     */
    public synchronized void connect(String host, int port) throws InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("RPC 客户端已关闭");
        }
        Channel currentChannel = channel;
        if (currentChannel != null && currentChannel.isActive()) {
            throw new IllegalStateException("RPC 客户端已经连接");
        }

        Bootstrap bootstrap = new Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel socketChannel) {
                    socketChannel.pipeline()
                        .addLast(new RpcMessageDecoder<>(RpcResponse.class, serializer, maxMessageLength))
                        .addLast(new RpcMessageEncoder<>(RpcRequest.class, serializer, maxMessageLength))
                        .addLast(responseHandler);
                }
            });
        channel = bootstrap.connect(host, port).sync().channel();
    }

    /**
     * 同步调用远端服务方法。
     *
     * @param serviceName 服务名
     * @param methodName 方法名
     * @param returnType 返回值类型
     * @param parameterTypes 参数类型
     * @param arguments 参数值
     * @param <T> 返回值类型参数
     * @return 转换后的调用结果
     * @throws RpcException 发送、超时、远端调用或结果转换失败时抛出
     */
    public <T> T invoke(
        String serviceName,
        String methodName,
        Class<T> returnType,
        Class<?>[] parameterTypes,
        Object[] arguments) {
        Objects.requireNonNull(returnType, "返回值类型不能为空");
        Objects.requireNonNull(parameterTypes, "参数类型不能为空");
        Objects.requireNonNull(arguments, "参数值不能为空");
        if (parameterTypes.length != arguments.length) {
            throw new IllegalArgumentException("参数类型数量必须与参数值数量一致");
        }

        Channel activeChannel = requireActiveChannel();
        if (activeChannel.eventLoop().inEventLoop() || responseEventLoop.inEventLoop()) {
            throw new RpcException(RpcErrorCode.INVALID_REQUEST, "不能在 Netty 事件循环线程中执行同步 RPC 调用");
        }

        RpcRequest request = createRequest(serviceName, methodName, parameterTypes, arguments);
        Promise<RpcResponse> responsePromise = responseEventLoop.newPromise();
        pendingCalls.put(request.requestId(), responsePromise);
        activeChannel.writeAndFlush(request).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                responseHandler.failCall(request.requestId(), responsePromise, new RpcException(
                    RpcErrorCode.CONNECTION_CLOSED,
                    "RPC 请求发送失败",
                    writeFuture.cause()));
            }
        });

        try {
            if (!responsePromise.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new RpcException(RpcErrorCode.TIMEOUT, "RPC 调用超时");
            }
            if (!responsePromise.isSuccess()) {
                throw invocationFailure(responsePromise.cause());
            }
            RpcResponse response = responsePromise.getNow();
            return convertResponse(response, returnType);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RpcException(RpcErrorCode.CONNECTION_CLOSED, "等待 RPC 响应时被中断", exception);
        } finally {
            pendingCalls.remove(request.requestId(), responsePromise);
        }
    }

    /**
     * 幂等关闭连接、未完成调用和事件循环组。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        responseHandler.failAll(new RpcException(RpcErrorCode.CONNECTION_CLOSED, "RPC 客户端已关闭"))
            .syncUninterruptibly();
        Channel currentChannel = channel;
        if (currentChannel != null) {
            currentChannel.close().syncUninterruptibly();
        }
        eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        responseEventLoop.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }

    private Channel requireActiveChannel() {
        Channel currentChannel = channel;
        if (closed.get() || currentChannel == null || !currentChannel.isActive()) {
            throw new RpcException(RpcErrorCode.CONNECTION_CLOSED, "RPC 客户端未连接");
        }
        return currentChannel;
    }

    private RpcRequest createRequest(
        String serviceName,
        String methodName,
        Class<?>[] parameterTypes,
        Object[] arguments) {
        List<String> parameterTypeNames = new ArrayList<>(parameterTypes.length);
        List<JsonNode> argumentNodes = new ArrayList<>(arguments.length);
        for (int index = 0; index < parameterTypes.length; index++) {
            parameterTypeNames.add(Objects.requireNonNull(parameterTypes[index], "参数类型不能为空").getName());
            argumentNodes.add(serializer.toTree(arguments[index]));
        }
        return new RpcRequest(
            UUID.randomUUID().toString(),
            serviceName,
            methodName,
            parameterTypeNames,
            argumentNodes);
    }

    private <T> T convertResponse(RpcResponse response, Class<T> returnType) {
        if (!response.success()) {
            RpcError error = response.error();
            throw new RpcException(error.code(), error.message());
        }
        if (returnType == Void.class || returnType == void.class) {
            return null;
        }
        return serializer.deserialize(response.result().getBytes(StandardCharsets.UTF_8), returnType);
    }

    private RpcException invocationFailure(Throwable cause) {
        if (cause instanceof RpcException rpcException) {
            return rpcException;
        }
        return new RpcException(RpcErrorCode.CONNECTION_CLOSED, "RPC 调用失败", cause);
    }
}
