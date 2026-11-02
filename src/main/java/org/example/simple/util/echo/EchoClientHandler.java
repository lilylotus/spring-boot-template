package org.example.simple.util.echo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个压测连接的处理器：连接建立后发送随机字符串，按累计偏移逐字节校验回显，收齐后按间隔发送下一次。
 * <p>
 * 每次请求的报文长度在 {@code [minMessageSize, maxMessageSize]} 内均匀随机，字符取自 {@code 0-9A-Za-z}，
 * 生成后即作为本次请求的期望回显。每个连接独立一个实例，所有状态仅在该连接所属的 EventLoop 线程中访问，无需同步。
 * 回显内容错误或回显超时时计数并主动关闭连接，避免后续字节错位造成误报。
 */
final class EchoClientHandler extends ChannelInboundHandlerAdapter {

    /** 日志记录器。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(EchoClientHandler.class);

    /** 随机字符串的字符集：可打印且不含换行，便于抓包排查。 */
    private static final byte[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);

    /** 随机报文最小长度。 */
    private final int minMessageSize;

    /** 随机报文最大长度。 */
    private final int maxMessageSize;

    /** 请求间隔（毫秒）。 */
    private final long intervalMillis;

    /** 回显超时（毫秒）。 */
    private final long responseTimeoutMillis;

    /** 全局统计。 */
    private final EchoLoadStatistics statistics;

    /** 会话是否正在停止；停止期间的关闭不计入被动断开，也不再发送新请求。 */
    private final BooleanSupplier stopping;

    /** 当前请求的报文，即期望的回显内容；发送后不再修改。 */
    private byte[] payload;

    /** 当前请求已收到并校验通过的字节数。 */
    private int received;

    /** 当前请求是否已发送且尚未收齐回显。 */
    private boolean awaitingResponse;

    /** 当前请求的发送时间（纳秒）。 */
    private long sendNanos;

    /** 当前请求的回显超时任务。 */
    private ScheduledFuture<?> timeoutFuture;

    /** 是否由本处理器因错误或超时主动关闭。 */
    private boolean closedLocally;

    /** 是否已计入在线连接，用于保证上线/下线计数成对。 */
    private boolean countedActive;

    /**
     * 创建连接处理器。
     *
     * @param config     压测配置
     * @param statistics 全局统计
     * @param stopping   会话是否正在停止
     */
    EchoClientHandler(
            EchoLoadTestConfig config, EchoLoadStatistics statistics,
            BooleanSupplier stopping) {
        this.minMessageSize = config.minMessageSize();
        this.maxMessageSize = config.maxMessageSize();
        this.intervalMillis = config.intervalMillis();
        this.responseTimeoutMillis = config.responseTimeoutMillis();
        this.statistics = statistics;
        this.stopping = stopping;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        countedActive = true;
        statistics.onActive();
        if (stopping.getAsBoolean()) {
            // 会话已开始停止时才建立成功的连接直接关闭
            ctx.close();
            return;
        }
        sendRequest(ctx);
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelTimeout();
        if (countedActive) {
            countedActive = false;
            statistics.onInactive(!closedLocally && !stopping.getAsBoolean());
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) {
            ReferenceCountUtil.release(msg);
            return;
        }
        try {
            int readable = buf.readableBytes();
            statistics.onBytesReceived(readable);
            int index = buf.readerIndex();
            int end = index + readable;
            for (; index < end; index++) {
                // 未在等待回显或本次回显已收齐时仍收到字节，说明服务端多写了数据
                if (!awaitingResponse || buf.getByte(index) != payload[received]) {
                    statistics.onContentError();
                    closeLocally(ctx);
                    return;
                }
                received++;
                if (received == payload.length) {
                    if (index + 1 < end) {
                        // 收齐后同一批数据仍有剩余字节，下一次请求尚未发出，必为多余数据
                        statistics.onContentError();
                        closeLocally(ctx);
                        return;
                    }
                    completeRequest(ctx);
                }
            }
        } finally {
            buf.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 连接重置等异常按被动断开处理，仅输出调试日志避免海量连接时刷屏
        LOGGER.debug("压测连接异常，关闭连接：{}", ctx.channel(), cause);
        ctx.close();
    }

    /** 发送一次请求并启动回显超时计时。 */
    private void sendRequest(ChannelHandlerContext ctx) {
        if (stopping.getAsBoolean() || !ctx.channel().isActive()) {
            return;
        }
        payload = randomPayload(minMessageSize, maxMessageSize);
        received = 0;
        awaitingResponse = true;
        sendNanos = System.nanoTime();
        statistics.onRequestSent(payload.length);
        ctx.writeAndFlush(Unpooled.wrappedBuffer(payload))
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        timeoutFuture = ctx.executor().schedule(
                () -> onResponseTimeout(ctx), responseTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** 收齐一次回显：记录延迟并按间隔安排下一次请求。 */
    private void completeRequest(ChannelHandlerContext ctx) {
        statistics.onRequestCompleted(System.nanoTime() - sendNanos);
        awaitingResponse = false;
        cancelTimeout();
        if (intervalMillis == 0) {
            sendRequest(ctx);
        } else {
            ctx.executor().schedule(() -> sendRequest(ctx), intervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    /** 回显超时：计数并主动关闭连接。 */
    private void onResponseTimeout(ChannelHandlerContext ctx) {
        if (awaitingResponse && ctx.channel().isActive()) {
            statistics.onResponseTimeout();
            closeLocally(ctx);
        }
    }

    /** 标记为主动关闭后关闭连接，该关闭不计入被动断开。 */
    private void closeLocally(ChannelHandlerContext ctx) {
        closedLocally = true;
        awaitingResponse = false;
        ctx.close();
    }

    /**
     * 生成长度在 {@code [minSize, maxSize]} 内均匀随机、字符取自 {@code 0-9A-Za-z} 的随机字符串字节。
     *
     * @param minSize 最小长度
     * @param maxSize 最大长度
     * @return 新分配的报文字节
     */
    static byte[] randomPayload(int minSize, int maxSize) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int size = random.nextInt(minSize, maxSize + 1);
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return bytes;
    }

    /** 取消当前回显超时任务。 */
    private void cancelTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }
}
