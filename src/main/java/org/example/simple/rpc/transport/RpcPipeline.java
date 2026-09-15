package org.example.simple.rpc.transport;

import io.netty.channel.*;
import io.netty.handler.ssl.*;
import io.netty.handler.timeout.*;

import org.example.simple.rpc.common.*;
import org.example.simple.rpc.config.RpcConfig;

import java.util.concurrent.TimeUnit;

/** 两端共享的传输管线，心跳不进入业务执行器。 */
public final class RpcPipeline {
    private RpcPipeline() {}

    public static void install(
            Channel channel,
            RpcConfig config,
            SerializerRegistry serializers,
            ByteBudget budget,
            SslContext ssl,
            String host,
            int port,
            ChannelInboundHandler handler) {
        ChannelPipeline pipeline = channel.pipeline();
        if (ssl != null) {
            SslHandler tls =
                    host == null
                            ? ssl.newHandler(channel.alloc())
                            : ssl.newHandler(channel.alloc(), host, port);
            if (host != null) {
                var parameters = tls.engine().getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.engine().setSSLParameters(parameters);
            }
            tls.setHandshakeTimeoutMillis(config.handshakeTimeoutMillis());
            pipeline.addLast("tls", tls);
        }
        BudgetHandler limits = new BudgetHandler(budget, config.maxChannelWriteBytes());
        pipeline.addLast("budget", limits)
                .addLast(
                        "idle",
                        new IdleStateHandler(
                                config.readIdleTimeoutMillis(),
                                config.heartbeatIntervalMillis(),
                                0,
                                TimeUnit.MILLISECONDS))
                .addLast(
                        "writeTimeout",
                        new WriteTimeoutHandler(config.writeTimeoutMillis(), TimeUnit.MILLISECONDS))
                .addLast("frames", new RpcMessageDecoder(config.maxMessageLength(), serializers))
                .addLast("encoder", new RpcMessageEncoder(config.maxMessageLength()))
                .addLast(
                        "heartbeat",
                        new ChannelInboundHandlerAdapter() {
                            private long ping;
                            private long sequence;

                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                RpcFrame frame = (RpcFrame) msg;
                                limits.framed(frame.body().length + 19);
                                if (frame.messageType() < 3) {
                                    ctx.fireChannelRead(msg);
                                    return;
                                }
                                limits.consumed(19);
                                if (frame.messageType() == RpcProtocol.PING) {
                                    ctx.writeAndFlush(
                                            new RpcFrame(
                                                    RpcProtocol.PONG,
                                                    (byte) 0,
                                                    frame.requestId(),
                                                    new byte[0]));
                                } else if (ping != frame.requestId()) {
                                    ctx.close();
                                } else {
                                    ping = 0;
                                }
                            }

                            @Override
                            public void userEventTriggered(
                                    ChannelHandlerContext ctx, Object event) {
                                if (event instanceof IdleStateEvent idle) {
                                    if (idle.state() == IdleState.READER_IDLE) {
                                        org.example.simple.rpc.monitoring.RpcTelemetry.event(
                                                "heartbeat.timeout");
                                        ctx.close();
                                    } else if (idle.state() == IdleState.WRITER_IDLE && ping == 0) {
                                        ping = ++sequence;
                                        ctx.writeAndFlush(
                                                new RpcFrame(
                                                        RpcProtocol.PING,
                                                        (byte) 0,
                                                        ping,
                                                        new byte[0]));
                                    }
                                } else {
                                    ctx.fireUserEventTriggered(event);
                                }
                            }

                            @Override
                            public void exceptionCaught(
                                    ChannelHandlerContext ctx, Throwable cause) {
                                ctx.close();
                            }
                        })
                .addLast("rpc", handler);
    }

    /** 在跨线程等待写入期间也持有字节预算。 */
    public static ChannelFuture write(Channel channel, RpcFrame frame, ByteBudget budget) {
        ChannelPromise promise = channel.newPromise();
        if (!budget.acquire(frame.body().length)) {
            promise.setFailure(new IllegalStateException("待编码报文预算已满"));
            channel.close();
            return promise;
        }
        Runnable action =
                () -> {
                    try {
                        if (channel.isActive()) {
                            channel.writeAndFlush(frame, promise);
                        } else {
                            promise.tryFailure(new IllegalStateException("连接已关闭"));
                        }
                    } finally {
                        budget.release(frame.body().length);
                    }
                };
        if (channel.eventLoop().inEventLoop()) {
            action.run();
        } else {
            try {
                channel.eventLoop().execute(action);
            } catch (RuntimeException error) {
                budget.release(frame.body().length);
                promise.tryFailure(error);
            }
        }
        return promise;
    }

    public static void consumed(Channel channel, RpcFrame frame) {
        ((BudgetHandler) channel.pipeline().get("budget")).consumed(frame.body().length + 19);
    }
}
