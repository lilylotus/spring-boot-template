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
    private final RpcServer server;
    private final Semaphore requests;

    RpcServerHandler(RpcServer server) {
        this.server = server;
        requests = new Semaphore(server.config.maxRequestsPerConnection());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcFrame frame) {
        if (frame.messageType() != RpcProtocol.REQUEST) {
            RpcPipeline.consumed(ctx.channel(), frame);
            ctx.close();
            return;
        }
        RpcExecutors.Lease lease =
            new RpcExecutors.Lease(() -> RpcPipeline.consumed(ctx.channel(), frame));
        RpcExecutors.Task task = new RpcExecutors.Task(() -> decode(ctx, frame, lease), lease);
        try {
            server.codec.execute(task);
        } catch (RejectedExecutionException e) {
            task.discard();
            ctx.close();
        }
    }

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

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
        ctx.close();
    }
}
