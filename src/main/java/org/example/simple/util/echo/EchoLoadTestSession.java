package org.example.simple.util.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次 echo 压测会话：负责传输选择、爬坡建连、周期统计输出、定时停止与资源释放。
 * <p>
 * 通过 {@link EchoServerUtils#start(EchoLoadTestConfig)} 创建并立即开始运行。{@link #stop()} 幂等且线程安全，
 * 可在任意非 IO 线程中调用；会话结束后可通过 {@link #awaitTermination()} 取得最终报告。
 */
public final class EchoLoadTestSession {

    /** 日志记录器。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(EchoLoadTestSession.class);

    /** 爬坡建连的调度周期（毫秒）。 */
    private static final long RAMP_TICK_MILLIS = 100;

    /** 每秒包含的爬坡周期数。 */
    private static final double TICKS_PER_SECOND = 1000.0 / RAMP_TICK_MILLIS;

    /** 停止时等待连接关闭与线程组退出的最长时间（秒）。 */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    /** 压测配置。 */
    private final EchoLoadTestConfig config;

    /** 全局统计。 */
    private final EchoLoadStatistics statistics;

    /** 已解析的目标地址。 */
    private final InetSocketAddress remoteAddress;

    /** 本地绑定地址列表，为空时由系统选择。 */
    private final List<InetSocketAddress> localAddresses;

    /** IO 线程组。 */
    private final EventLoopGroup ioGroup;

    /** 建连引导。 */
    private final Bootstrap bootstrap;

    /** 所有已发起的连接，关闭后自动移除。 */
    private final ChannelGroup channels;

    /** 爬坡、统计输出与定时停止使用的单线程调度器。 */
    private final ScheduledThreadPoolExecutor scheduler;

    /** 是否已进入停止流程。 */
    private final AtomicBoolean stopping = new AtomicBoolean();

    /** 停止流程完成信号。 */
    private final CountDownLatch terminated = new CountDownLatch(1);

    /** 会话开始时间（纳秒）。 */
    private final long startNanos;

    /** 实际使用的传输类型名称。 */
    private final String transportName;

    /** 爬坡建连任务，全部发起后取消。 */
    private volatile ScheduledFuture<?> rampFuture;

    /** 爬坡累积的建连额度，仅在调度线程中访问。 */
    private double connectCredit;

    /** 上一次区间统计快照，仅在调度线程中访问。 */
    private EchoLoadReport previousReport;

    /** 最终报告，停止流程完成后可见。 */
    private volatile EchoLoadReport finalReport;

    /** 创建会话并初始化网络资源，不发起连接；由 {@link #begin()} 启动。 */
    EchoLoadTestSession(EchoLoadTestConfig config) {
        this.config = config;
        this.statistics = new EchoLoadStatistics(config.clients());
        InetSocketAddress remote = new InetSocketAddress(config.host(), config.port());
        if (remote.isUnresolved()) {
            throw new IllegalArgumentException("无法解析目标地址：" + config.host());
        }
        this.remoteAddress = remote;
        this.localAddresses = config.localAddresses().stream()
                .map(EchoLoadTestSession::resolveLocalAddress)
                .toList();

        boolean epoll = Epoll.isAvailable();
        IoHandlerFactory ioHandlerFactory = epoll ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();
        this.transportName = epoll ? "epoll" : "nio";
        this.ioGroup = new MultiThreadIoEventLoopGroup(
                config.ioThreads(), new DefaultThreadFactory("echo-load-io"), ioHandlerFactory);
        this.bootstrap = new Bootstrap()
                .group(ioGroup)
                .channel(epoll ? EpollSocketChannel.class : NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(
                                new EchoClientHandler(config, statistics, stopping::get));
                    }
                });
        this.channels = new DefaultChannelGroup("echo-load-clients", GlobalEventExecutor.INSTANCE);

        this.scheduler = new ScheduledThreadPoolExecutor(1, new DefaultThreadFactory("echo-load-scheduler"));
        // 停止后不再执行尚未到期的延迟任务（如定时停止任务）
        this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.startNanos = System.nanoTime();
    }

    /** 启动爬坡建连、统计输出与定时停止任务。 */
    void begin() {
        LOGGER.info("开始压测：目标={}:{} 连接数={} 时长={} 建连速率={}/s 最大并发建连={} 间隔={}ms 报文={}-{}B "
                        + "IO线程={} 传输={} 本地地址={}",
                config.host(), config.port(), config.clients(),
                config.isForever() ? "一直运行" : config.durationSeconds() + "s",
                config.connectRate(), config.maxPendingConnects(), config.intervalMillis(),
                config.minMessageSize(), config.maxMessageSize(), config.ioThreads(), transportName,
                config.localAddresses().isEmpty() ? "系统选择" : config.localAddresses());
        rampFuture = scheduler.scheduleAtFixedRate(
                this::safeRampTick, 0, RAMP_TICK_MILLIS, TimeUnit.MILLISECONDS);
        long reportMillis = TimeUnit.SECONDS.toMillis(config.reportIntervalSeconds());
        scheduler.scheduleAtFixedRate(this::safeReport, reportMillis, reportMillis, TimeUnit.MILLISECONDS);
        if (!config.isForever()) {
            scheduler.schedule(this::stop, config.durationSeconds(), TimeUnit.SECONDS);
        }
    }

    /**
     * 实际使用的传输类型。
     *
     * @return {@code epoll} 或 {@code nio}
     */
    public String transportName() {
        return transportName;
    }

    /**
     * 会话是否已进入停止流程。
     *
     * @return 已开始停止时为 {@code true}
     */
    public boolean isStopping() {
        return stopping.get();
    }

    /**
     * 返回当前统计快照，不影响压测运行；会话结束后返回最终报告。
     *
     * @return 统计快照
     */
    public EchoLoadReport snapshot() {
        EchoLoadReport report = finalReport;
        return report != null ? report : statistics.snapshot(elapsedMillis());
    }

    /**
     * 停止压测：停止建连与发送、关闭所有连接、释放线程资源并输出汇总。
     * <p>
     * 幂等：重复调用或并发调用均等待同一停止流程完成并返回同一份最终报告。不得在 IO 线程中调用。
     *
     * @return 最终统计报告
     */
    public EchoLoadReport stop() {
        if (!stopping.compareAndSet(false, true)) {
            return awaitTermination();
        }
        try {
            scheduler.shutdown();
            // 先取快照再关闭连接，使"结束时在线连接"反映停止前的真实状态
            EchoLoadReport beforeClose = statistics.snapshot(elapsedMillis());
            channels.close().awaitUninterruptibly(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ioGroup.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .awaitUninterruptibly(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            EchoLoadReport afterClose = statistics.snapshot(beforeClose.elapsedMillis());
            finalReport = new EchoLoadReport(
                    afterClose.elapsedMillis(), afterClose.targetClients(),
                    afterClose.initiated(), afterClose.pending(),
                    beforeClose.active(), afterClose.peakActive(),
                    afterClose.connectFailed(), afterClose.passiveClosed(),
                    afterClose.requestsSent(), afterClose.requestsCompleted(),
                    afterClose.responseTimeouts(), afterClose.contentErrors(),
                    afterClose.bytesSent(), afterClose.bytesReceived(),
                    afterClose.latencySumNanos(), afterClose.latencyMaxNanos());
            LOGGER.info("{}", finalReport.toSummary());
        } finally {
            if (finalReport == null) {
                finalReport = statistics.snapshot(elapsedMillis());
            }
            terminated.countDown();
        }
        return finalReport;
    }

    /**
     * 阻塞等待会话结束（定时到期或被 {@link #stop()}），返回最终报告。
     *
     * @return 最终统计报告
     */
    public EchoLoadReport awaitTermination() {
        boolean interrupted = false;
        while (true) {
            try {
                terminated.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return finalReport;
    }

    /**
     * 阻塞等待会话结束，最多等待指定时间。
     *
     * @param timeout 等待时长
     * @param unit    时长单位
     * @return 会话是否已结束
     * @throws InterruptedException 等待期间被中断
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return terminated.await(timeout, unit);
    }

    /** 爬坡周期任务的异常保护，避免异常导致周期任务被取消。 */
    private void safeRampTick() {
        try {
            rampTick();
        } catch (RuntimeException e) {
            LOGGER.error("爬坡建连任务异常", e);
        }
    }

    /** 按建连速率、并发建连上限和剩余目标数发起本周期的建连。 */
    private void rampTick() {
        if (stopping.get()) {
            return;
        }
        double perTick = config.connectRate() / TICKS_PER_SECOND;
        // 额度上限为一个周期的配额（至少 1），避免因并发建连受限而积压后瞬间爆发
        connectCredit = Math.min(connectCredit + perTick, Math.max(1.0, perTick));
        long remaining = config.clients() - statistics.initiated();
        long pendingRoom = config.maxPendingConnects() - statistics.pending();
        long allowed = Math.min((long) connectCredit, Math.min(remaining, pendingRoom));
        for (long i = 0; i < allowed && !stopping.get(); i++) {
            connectOne();
        }
        if (allowed > 0) {
            connectCredit -= allowed;
        }
        if (statistics.initiated() >= config.clients()) {
            ScheduledFuture<?> future = rampFuture;
            if (future != null) {
                future.cancel(false);
            }
            LOGGER.info("已发起全部 {} 个建连", config.clients());
        }
    }

    /** 发起一个连接，并在结果返回时更新统计。 */
    private void connectOne() {
        long sequence = statistics.onConnectInitiated();
        ChannelFuture future = localAddresses.isEmpty()
                ? bootstrap.connect(remoteAddress)
                : bootstrap.connect(remoteAddress, localAddresses.get((int) (sequence % localAddresses.size())));
        channels.add(future.channel());
        future.addListener(f -> {
            statistics.onConnectFinished();
            if (!f.isSuccess() && !stopping.get()) {
                statistics.onConnectFailed();
                LOGGER.debug("建连失败：{}", f.cause().toString());
            }
        });
    }

    /** 统计输出任务的异常保护。 */
    private void safeReport() {
        try {
            EchoLoadReport current = statistics.snapshot(elapsedMillis());
            LOGGER.info("{}", current.toIntervalLine(previousReport, statistics.takeIntervalMaxLatencyNanos()));
            previousReport = current;
        } catch (RuntimeException e) {
            LOGGER.error("统计输出任务异常", e);
        }
    }

    /** 从开始到现在经过的毫秒数。 */
    private long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** 解析本地绑定地址，端口由系统分配。 */
    private static InetSocketAddress resolveLocalAddress(String host) {
        InetSocketAddress address = new InetSocketAddress(host, 0);
        if (address.isUnresolved()) {
            throw new IllegalArgumentException("无法解析本地绑定地址：" + host);
        }
        return address;
    }
}
