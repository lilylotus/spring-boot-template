package org.example.simple.rpc.server;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.example.simple.rpc.common.*;
import org.example.simple.rpc.monitoring.RpcTelemetry;
import org.example.simple.rpc.transport.RpcExecutors;
import org.example.simple.rpc.transport.RpcPipeline;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/**
 * 接收帧后转交有界解码池和业务池。
 */
final class RpcServerHandler extends SimpleChannelInboundHandler<RpcFrame> {
    /** 所属服务端实例，线程池、准入信号量与注册表均取自它。 */
    private final RpcServer server;
    /** 本连接的在途请求许可，许可数为单连接最大在途请求数，防止单个客户端占满服务端容量。 */
    private final Semaphore requests;

    /**
     * 为一条客户端连接创建处理器。
     *
     * @param server 所属服务端实例
     */
    RpcServerHandler(RpcServer server) {
        this.server = server;
        requests = new Semaphore(server.config.maxRequestsPerConnection());
    }

    /**
     * 接收请求帧并转交解码线程池，非请求帧直接关闭连接。
     *
     * <p>用租约（{@link RpcExecutors.Lease}）承接报文字节的归还责任：无论任务正常执行、被拒绝还是
     * 在停机时被丢弃，字节预算都恰好归还一次，不会泄漏。解码线程池已满时直接关闭连接，
     * 让过载以明确失败暴露。
     *
     * @param ctx 通道处理上下文
     * @param frame 已拆帧的消息
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcFrame frame) {
        if (frame.messageType() != RpcProtocol.REQUEST) {
            RpcPipeline.consumed(server.budget, frame);
            ctx.close();
            return;
        }
        RpcExecutors.Lease lease =
            new RpcExecutors.Lease(() -> RpcPipeline.consumed(server.budget, frame));
        RpcExecutors.Task task = new RpcExecutors.Task(() -> decode(ctx, frame, lease), lease);
        try {
            server.codec.execute(task);
        } catch (RejectedExecutionException e) {
            task.discard();
            ctx.close();
        }
    }

    /**
     * 在解码线程内反序列化请求，通过多层准入后再提交业务线程池执行。
     *
     * <p>准入检查按代价从低到高依次进行，任何一层不通过都立即回 {@link RpcErrorCode#SERVER_BUSY}：
     *
     * <ol>
     *   <li>服务端是否正在停机
     *   <li>该服务的令牌桶限流是否放行
     *   <li>本连接在途请求是否超限
     *   <li>服务端全局在途请求是否超限
     * </ol>
     *
     * <p>业务任务执行前先比较排队耗时与请求声明的超时预算：已经超时的请求不再执行业务方法，
     * 直接回 {@link RpcErrorCode#TIMEOUT}，避免为客户端已放弃的请求做无用功。
     * 任务的清理动作统一归还连接许可、全局许可与报文字节，因此被拒绝或被丢弃时也不会泄漏。
     *
     * @param ctx 通道处理上下文
     * @param frame 待解码的请求帧
     * @param lease 报文字节租约，提交业务任务前会增加一次引用
     */
    private void decode(ChannelHandlerContext ctx, RpcFrame frame, RpcExecutors.Lease lease) {
        try {
            MessageSerializer serializer = server.serializers.get(frame.serializerId());
            RpcRequest request = serializer.deserialize(frame.body(), RpcRequest.class);
            if (server.draining.get()
                || !server.rateAllowed(request.serviceName())
                || !requests.tryAcquire()) {
                respond(
                    ctx,
                    frame,
                    serializer,
                    RpcResponse.failure(RpcErrorCode.SERVER_BUSY, "服务端繁忙或正在停机"));
                return;
            }
            if (!server.inflight.tryAcquire()) {
                requests.release();
                respond(
                    ctx,
                    frame,
                    serializer,
                    RpcResponse.failure(RpcErrorCode.SERVER_BUSY, "服务端并发超过上限"));
                return;
            }
            lease.retain();
            RpcExecutors.Task businessTask =
                new RpcExecutors.Task(
                    () -> {
                        try {
                            long elapsed = (System.nanoTime() - frame.receivedNanos()) / 1_000_000;
                            RpcTelemetry.Call telemetry = RpcTelemetry.start("server", request.traceContext());
                            try (var scope = telemetry.context().makeCurrent()) {
                                RpcResponse result =
                                    elapsed >= request.timeoutMillis()
                                        ? RpcResponse.failure(
                                        RpcErrorCode.TIMEOUT, "请求排队超时")
                                        : new RpcRequestDispatcher(
                                        server.registry, serializer)
                                        .dispatch(request);
                                respond(ctx, frame, serializer, result);
                                telemetry.finish(
                                    result.success()
                                        ? "success"
                                        : result.error().code().name());
                            }
                        } catch (RuntimeException error) {
                            ctx.close();
                        }
                    },
                    () -> {
                        requests.release();
                        server.inflight.release();
                        lease.run();
                    });
            try {
                server.business.execute(businessTask);
            } catch (RejectedExecutionException e) {
                businessTask.discard();
                respond(
                    ctx,
                    frame,
                    serializer,
                    RpcResponse.failure(RpcErrorCode.SERVER_BUSY, "业务队列已满"));
            }
        } catch (RuntimeException error) {
            ctx.close();
        }
    }

    /**
     * 编码并写出响应帧，请求编号与序列化标识回填自请求帧。
     *
     * <p>连接已关闭时直接丢弃响应；写入失败则关闭连接，避免连接处于半故障状态继续接收请求。
     *
     * @param ctx 通道处理上下文
     * @param frame 对应的请求帧
     * @param serializer 与请求一致的序列化器
     * @param response 待写出的响应
     */
    private void respond(
        ChannelHandlerContext ctx,
        RpcFrame frame,
        MessageSerializer serializer,
        RpcResponse response) {
        if (ctx.channel().isActive()) {
            RpcPipeline.write(
                    ctx.channel(),
                    new RpcFrame(
                        RpcProtocol.RESPONSE,
                        frame.serializerId(),
                        frame.requestId(),
                        serializer.serialize(response)),
                    server.budget)
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        }
    }

    /**
     * 处理链异常时关闭连接，在途资源由各任务的清理动作归还。
     *
     * @param ctx 通道处理上下文
     * @param error 异常原因
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
        ctx.close();
    }
}
