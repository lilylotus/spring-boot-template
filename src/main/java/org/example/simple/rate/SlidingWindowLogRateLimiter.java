package org.example.simple.rate;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * 滑动窗口限流器（滑动日志法）：保证任意长度为 {@code window} 的时间区间内放行的许可不超过 {@code limit}。
 * <p>
 * 原理：按时间顺序记录每次放行（时间戳与许可数），并维护窗口内许可总数。判断时先移除时间戳不晚于
 * {@code now - window} 的记录，即统计左开右闭区间 {@code (now - window, now]}，再判断加上本次许可是否超限。
 * 每次放行只记录一条，一次获取多个许可不会放大开销。
 * <p>
 * 精度：精确，不存在 {@link FixedWindowRateLimiter} 的边界突发问题。
 * <p>
 * 复杂度：每次判断均摊 O(1)（每条记录只入队、出队各一次）；空间 O(limit)，记录数最多为 {@code limit}。
 * {@code limit} 很大（如百万级）时内存开销明显，此时请使用 {@link SlidingWindowCounterRateLimiter}。
 * <p>
 * 适用场景：上限较小、要求严格不超限的场景，如登录失败次数限制、敏感接口调用限制。
 * <p>
 * 线程安全：所有判断与修改在对象锁内完成。
 */
public class SlidingWindowLogRateLimiter implements RateLimiter {

    /** 任意窗口内允许放行的许可上限。 */
    private final int limit;

    /** 窗口长度（纳秒）。 */
    private final long windowNanos;

    /** 纳秒时间源。 */
    private final LongSupplier clock;

    /** 按时间顺序排列的放行记录，队首最早。 */
    private final Deque<Grant> grants = new ArrayDeque<>();

    /** 窗口内已放行的许可总数，等于 {@link #grants} 中许可数之和。 */
    private long total;

    /** 最近一次读取的时间，用于防止时间回退。 */
    private long lastNanos;

    /**
     * 创建滑动日志限流器。
     *
     * @param limit 任意窗口内允许放行的许可上限，必须为正数
     * @param window 窗口长度，必须为正
     * @throws IllegalArgumentException 参数不合法时抛出
     * @throws NullPointerException 窗口为 {@code null} 时抛出
     */
    public SlidingWindowLogRateLimiter(int limit, Duration window) {
        this(limit, window, RateLimiters.SYSTEM_NANO_TIME);
    }

    /**
     * 使用指定时间源创建滑动日志限流器，仅供同包测试注入假时钟。
     *
     * @param limit 任意窗口内允许放行的许可上限
     * @param window 窗口长度
     * @param nanoTimeSource 纳秒时间源
     */
    SlidingWindowLogRateLimiter(
            int limit, Duration window,
            LongSupplier nanoTimeSource) {
        this.limit = RateLimiters.requirePositive(limit, "limit");
        this.windowNanos = RateLimiters.toPositiveNanos(window);
        this.clock = RateLimiters.requireClock(nanoTimeSource);
        this.lastNanos = clock.getAsLong();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean tryAcquire(int permits) {
        RateLimiters.checkPermits(permits);
        if (permits > limit) {
            return false;
        }
        lastNanos = RateLimiters.monotonicNow(clock, lastNanos);
        // 移除已滑出窗口的记录：距今不小于窗口长度
        while (!grants.isEmpty() && lastNanos - grants.peekFirst().timestamp() >= windowNanos) {
            total -= grants.pollFirst().permits();
        }
        if (total + permits > limit) {
            return false;
        }
        grants.addLast(new Grant(lastNanos, permits));
        total += permits;
        return true;
    }

    /**
     * 返回任意窗口内的许可上限。
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

    /**
     * 一次放行记录。
     *
     * @param timestamp 放行时间（纳秒）
     * @param permits 放行的许可数
     */
    private record Grant(long timestamp, int permits) {
    }
}
