package org.example.simple.rate;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 固定窗口计数限流器：把时间切分为首尾相接的固定窗口，每个窗口内最多放行 {@code limit} 个许可。
 * <p>
 * 原理：窗口以限流器创建时刻对齐，记录当前窗口起点与已放行计数；当前时间越过窗口终点时，
 * 窗口起点前移整数个窗口长度（长时间空闲时用整除一次跳过多个窗口）并清零计数。
 * <p>
 * 边界突发问题：在某个窗口末尾放满 {@code limit} 个许可，紧接着在下一窗口开头再放满 {@code limit} 个，
 * 则跨边界的极短时间内共放行 {@code 2·limit} 个许可，超过预期速率。需要平滑限制时请使用
 * {@link SlidingWindowLogRateLimiter} 或 {@link SlidingWindowCounterRateLimiter}。
 * <p>
 * 复杂度：每次判断 O(1)，空间 O(1)。
 * <p>
 * 适用场景：对精度要求不高、实现与理解成本优先的计数类限制，如"每分钟最多发送 N 条短信"。
 * <p>
 * 线程安全：所有判断与修改在对象锁内完成。
 */
public class FixedWindowRateLimiter implements RateLimiter {

    /** 每个窗口允许放行的许可上限。 */
    private final int limit;

    /** 窗口长度（纳秒）。 */
    private final long windowNanos;

    /** 纳秒时间源。 */
    private final LongSupplier clock;

    /** 当前窗口起点（纳秒）。 */
    private long windowStart;

    /** 最近一次读取的时间，用于防止时间回退。 */
    private long lastNanos;

    /** 当前窗口已放行的许可数。 */
    private long count;

    /**
     * 创建固定窗口限流器。
     *
     * @param limit 每个窗口允许放行的许可上限，必须为正数
     * @param window 窗口长度，必须为正
     * @throws IllegalArgumentException 参数不合法时抛出
     * @throws NullPointerException 窗口为 {@code null} 时抛出
     */
    public FixedWindowRateLimiter(int limit, Duration window) {
        this(limit, window, RateLimiters.SYSTEM_NANO_TIME);
    }

    /**
     * 使用指定时间源创建固定窗口限流器，仅供同包测试注入假时钟。
     *
     * @param limit 每个窗口允许放行的许可上限
     * @param window 窗口长度
     * @param nanoTimeSource 纳秒时间源
     */
    FixedWindowRateLimiter(
            int limit, Duration window,
            LongSupplier nanoTimeSource) {
        this.limit = RateLimiters.requirePositive(limit, "limit");
        this.windowNanos = RateLimiters.toPositiveNanos(window);
        this.clock = RateLimiters.requireClock(nanoTimeSource);
        this.windowStart = clock.getAsLong();
        this.lastNanos = windowStart;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean tryAcquire(int permits) {
        RateLimiters.checkPermits(permits);
        if (permits > limit) {
            return false;
        }
        lastNanos = RateLimiters.monotonicNow(clock, lastNanos);
        long elapsed = lastNanos - windowStart;
        if (elapsed >= windowNanos) {
            // 跳过整数个窗口，使窗口起点保持与创建时刻对齐
            windowStart += elapsed / windowNanos * windowNanos;
            count = 0;
        }
        if (count + permits > limit) {
            return false;
        }
        count += permits;
        return true;
    }

    /**
     * 返回每个窗口的许可上限。
     *
     * @return 许可上限
     */
    public int limit() {
        return limit;
    }

    /**
     * 返回窗口长度。
     *
     * @return 窗口长度
     */
    public Duration window() {
        return Duration.ofNanos(windowNanos);
    }
}
