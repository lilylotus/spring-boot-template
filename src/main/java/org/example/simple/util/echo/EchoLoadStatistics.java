package org.example.simple.util.echo;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * echo 压测的线程安全计数器，由爬坡调度线程与各 IO 线程并发更新。
 */
final class EchoLoadStatistics {

    /** 目标连接数。 */
    private final int targetClients;

    /** 已发起建连数。 */
    private final AtomicLong initiated = new AtomicLong();

    /** 建连中连接数。 */
    private final AtomicLong pending = new AtomicLong();

    /** 当前在线连接数。 */
    private final AtomicLong active = new AtomicLong();

    /** 峰值在线连接数。 */
    private final AtomicLong peakActive = new AtomicLong();

    /** 建连失败数。 */
    private final LongAdder connectFailed = new LongAdder();

    /** 被动断开数。 */
    private final LongAdder passiveClosed = new LongAdder();

    /** 已发送请求数。 */
    private final LongAdder requestsSent = new LongAdder();

    /** 已完成请求数。 */
    private final LongAdder requestsCompleted = new LongAdder();

    /** 回显超时数。 */
    private final LongAdder responseTimeouts = new LongAdder();

    /** 内容错误数。 */
    private final LongAdder contentErrors = new LongAdder();

    /** 发送字节数。 */
    private final LongAdder bytesSent = new LongAdder();

    /** 接收字节数。 */
    private final LongAdder bytesReceived = new LongAdder();

    /** 延迟总和（纳秒）。 */
    private final LongAdder latencySumNanos = new LongAdder();

    /** 全程最大延迟（纳秒）。 */
    private final AtomicLong latencyMaxNanos = new AtomicLong();

    /** 当前统计区间内的最大延迟（纳秒），每次输出区间统计时清零。 */
    private final AtomicLong intervalLatencyMaxNanos = new AtomicLong();

    /** 创建指定目标连接数的计数器。 */
    EchoLoadStatistics(int targetClients) {
        this.targetClients = targetClients;
    }

    /** 记录发起一次建连，返回本次建连的序号（从 0 开始）。 */
    long onConnectInitiated() {
        pending.incrementAndGet();
        return initiated.getAndIncrement();
    }

    /** 记录建连结果（成功或失败）已返回，建连中数量减一。 */
    void onConnectFinished() {
        pending.decrementAndGet();
    }

    /** 记录建连失败。 */
    void onConnectFailed() {
        connectFailed.increment();
    }

    /** 记录连接上线并更新峰值。 */
    void onActive() {
        long current = active.incrementAndGet();
        peakActive.accumulateAndGet(current, Math::max);
    }

    /** 记录连接下线；{@code passive} 为真时计入被动断开。 */
    void onInactive(boolean passive) {
        active.decrementAndGet();
        if (passive) {
            passiveClosed.increment();
        }
    }

    /** 记录发送一次请求。 */
    void onRequestSent(int bytes) {
        requestsSent.increment();
        bytesSent.add(bytes);
    }

    /** 记录接收字节。 */
    void onBytesReceived(int bytes) {
        bytesReceived.add(bytes);
    }

    /** 记录一次请求完成及其延迟。 */
    void onRequestCompleted(long latencyNanos) {
        requestsCompleted.increment();
        latencySumNanos.add(latencyNanos);
        latencyMaxNanos.accumulateAndGet(latencyNanos, Math::max);
        intervalLatencyMaxNanos.accumulateAndGet(latencyNanos, Math::max);
    }

    /** 记录回显超时。 */
    void onResponseTimeout() {
        responseTimeouts.increment();
    }

    /** 记录回显内容错误。 */
    void onContentError() {
        contentErrors.increment();
    }

    /** 当前已发起建连数。 */
    long initiated() {
        return initiated.get();
    }

    /** 当前建连中连接数。 */
    long pending() {
        return pending.get();
    }

    /** 取出并清零当前区间最大延迟（纳秒）。 */
    long takeIntervalMaxLatencyNanos() {
        return intervalLatencyMaxNanos.getAndSet(0);
    }

    /** 生成统计快照。 */
    EchoLoadReport snapshot(long elapsedMillis) {
        return new EchoLoadReport(
                elapsedMillis, targetClients,
                initiated.get(), pending.get(),
                active.get(), peakActive.get(),
                connectFailed.sum(), passiveClosed.sum(),
                requestsSent.sum(), requestsCompleted.sum(),
                responseTimeouts.sum(), contentErrors.sum(),
                bytesSent.sum(), bytesReceived.sum(),
                latencySumNanos.sum(), latencyMaxNanos.get());
    }
}
