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
    /** 工具类，禁止实例化。 */
    private RpcPipeline() {}

    /**
     * 为一条数据连接安装完整的 RPC 传输管线。
     *
     * <p>处理器顺序即数据流经顺序，入站为 tls、budget、idle、frames、heartbeat、rpc：
     *
     * <ul>
     *   <li>tls：可选的传输加密；客户端侧额外按 HTTPS 规则校验服务端证书主机名，防止中间人替换证书
     *   <li>budget：放在拆帧之前统计入站与待写字节，使半包数据也受内存预算约束
     *   <li>idle：读空闲触发关闭（对端已失联），写空闲触发心跳（保活并探测链路）
     *   <li>writeTimeout：单次写入超时保护
     *   <li>frames/encoder：固定头协议的拆帧与编码
     *   <li>heartbeat：就地处理心跳帧，不往后传递，因此心跳不会占用业务线程
     *   <li>rpc：客户端或服务端各自的业务处理器
     * </ul>
     *
     * @param channel 待安装管线的连接
     * @param config 生产参数
     * @param serializers 序列化器注册表
     * @param budget 实例级字节预算
     * @param ssl TLS 上下文；为 {@code null} 时使用明文传输
     * @param host 客户端侧的目标主机名，用于证书主机名校验；服务端侧传 {@code null}
     * @param port 客户端侧的目标端口；服务端侧传 0
     * @param handler 管线末端的业务处理器
     * @throws IllegalStateException 当连接选项未实际生效时抛出
     */
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
                            /** 已发出但尚未收到 PONG 的心跳编号；0 表示当前无待确认心跳。 */
                            private long ping;
                            /** 心跳编号生成器，仅在本连接的事件循环线程递增。 */
                            private long sequence;

                            /**
                             * 登记已拆帧字节，业务帧继续向后传递，心跳帧就地处理。
                             *
                             * <p>收到 PING 立即回 PONG；收到 PONG 时校验编号是否为本端待确认的心跳，
                             * 编号不符说明对端行为异常，直接关闭连接。
                             *
                             * @param ctx 通道处理上下文
                             * @param msg 已拆帧的消息
                             */
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

                            /**
                             * 处理空闲事件：读空闲判定对端失联并关闭，写空闲发送心跳保活。
                             *
                             * <p>仅在没有待确认心跳时才发新心跳，避免连接不可用时心跳无限堆积。
                             *
                             * @param ctx 通道处理上下文
                             * @param event 触发的事件，非空闲事件原样向后传递
                             */
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

                            /**
                             * 心跳处理异常时关闭连接。
                             *
                             * @param ctx 通道处理上下文
                             * @param cause 异常原因
                             */
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

    /**
     * 断言某个连接选项已生效。
     *
     * @param applied 选项是否已生效
     * @param option 选项名，用于异常信息中定位问题
     * @throws IllegalStateException 选项未生效时抛出
     */
    private static void require(boolean applied, String option) {
        if (!applied) {
            throw new IllegalStateException("连接选项未生效：" + option);
        }
    }

    /**
     * 在跨线程等待写入期间也持有字节预算。
     *
     * <p>编码完成的报文从工作线程提交到事件循环期间仍占用内存，因此先申请预算再提交，并在真正写出（或提交失败）后归还。
     * 已在事件循环线程时直接执行，避开一次多余的任务调度。
     *
     * @param channel 目标连接
     * @param frame 待写出的消息帧
     * @param budget 实例级字节预算
     * @return 写入结果；预算不足或连接不可用时以异常完成
     */
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
