package org.example.simple.rpc.transport;

import io.netty.buffer.PooledByteBufAllocator;
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
        verifyOptions(channel, config);
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

    /**
     * 校验数据连接选项已经实际生效。
     *
     * <p>Netty 在选项不被 Channel 支持时只记录警告并继续运行，必须在安装管线前读回实际值，
     * 否则生产参数会被静默忽略而无法在启动阶段发现。
     *
     * @param channel 已经应用过选项的数据连接
     * @param config 期望生效的生产参数
     * @throws IllegalStateException 当任意选项未生效时抛出，并指明具体选项名
     */
    private static void verifyOptions(Channel channel, RpcConfig config) {
        ChannelConfig options = channel.config();
        require(Boolean.TRUE.equals(options.getOption(ChannelOption.TCP_NODELAY)), "TCP_NODELAY");
        require(Boolean.TRUE.equals(options.getOption(ChannelOption.SO_KEEPALIVE)), "SO_KEEPALIVE");
        require(Boolean.TRUE.equals(options.getOption(ChannelOption.AUTO_READ)), "AUTO_READ");
        require(
                Boolean.FALSE.equals(options.getOption(ChannelOption.ALLOW_HALF_CLOSURE)),
                "ALLOW_HALF_CLOSURE");
        require(options.getAllocator() == PooledByteBufAllocator.DEFAULT, "ALLOCATOR");
        WriteBufferWaterMark mark = options.getWriteBufferWaterMark();
        require(
                mark != null
                        && mark.low() == config.writeLowWaterMark()
                        && mark.high() == config.writeHighWaterMark(),
                "WRITE_BUFFER_WATER_MARK");
    }

    private static void require(boolean applied, String option) {
        if (!applied) {
            throw new IllegalStateException("连接选项未生效：" + option);
        }
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

    /**
     * 归还一个已拆帧报文占用的字节预算。
     *
     * <p>直接面向端级预算释放，而不是通过管线查找处理器：连接被关闭后管线会被拆除，
     * 此时仍有报文停留在解码或业务队列中，必须保证这些字节最终回到预算。
     *
     * @param budget 该客户端或服务端实例的报文字节预算
     * @param frame 已经完成处理或被丢弃的报文
     */
    public static void consumed(ByteBudget budget, RpcFrame frame) {
        budget.release(frame.body().length + RpcProtocol.HEADER_LENGTH);
    }
}
