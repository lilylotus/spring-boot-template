package org.example.simple.rate;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 滑动窗口计数限流器：把窗口划分为 {@code buckets} 个等长子窗口，用最近 {@code buckets} 个子窗口的计数之和
 * 近似滑动窗口内的放行量。
 * <p>
 * 原理：子窗口以限流器创建时刻对齐，长度为 {@code window / buckets}，计数保存在长度为 {@code buckets} 的环形数组中，
 * 每格同时记录所属子窗口的起点。判断时按当前时间定位所在子窗口及其格子，格子记录的起点已过期则先清零复用；
 * 再累加起点距当前子窗口不足一个窗口长度的格子计数，加上本次许可不超过 {@code limit} 即放行。
 * <p>
 * 精度：统计范围按子窗口粒度滑动，误差上界约为一个子窗口内的放行量；{@code buckets} 越大越接近
 * {@link SlidingWindowLogRateLimiter} 的精确结果，判断成本也越高。{@code buckets = 1} 时退化为固定窗口计数。
 * <p>
 * 复杂度：每次判断 O(buckets)，空间 O(buckets)，与上限无关。
 * <p>
 * 适用场景：上限较大、需要平滑限制且可接受少量误差的场景，如接口 QPS 限制（Sentinel 采用同类结构统计流量）。
 * <p>
 * 线程安全：所有判断与修改在对象锁内完成。
 */
public class SlidingWindowCounterRateLimiter implements RateLimiter {

    /** 默认子窗口数。 */
    public static final int DEFAULT_BUCKETS = 10;

    /** 窗口内允许放行的许可上限。 */
    private final int limit;

    /** 窗口长度（纳秒）。 */
    private final long windowNanos;

    /** 子窗口长度（纳秒）。 */
    private final long bucketNanos;

    /** 纳秒时间源。 */
    private final LongSupplier clock;

    /** 子窗口对齐的起点，即限流器创建时刻。 */
    private final long origin;

    /** 每个格子当前所属子窗口的起点。 */
    private final long[] bucketStarts;

    /** 每个格子的放行计数。 */
    private final long[] counts;

    /** 最近一次读取的时间，用于防止时间回退。 */
    private long lastNanos;

    /**
     * 使用默认子窗口数 {@value #DEFAULT_BUCKETS} 创建滑动窗口计数限流器。
     *
     * @param limit 窗口内允许放行的许可上限，必须为正数
     * @param window 窗口长度，必须为正且纳秒数能被 {@value #DEFAULT_BUCKETS} 整除
     * @throws IllegalArgumentException 参数不合法时抛出
     * @throws NullPointerException 窗口为 {@code null} 时抛出
     */
    public SlidingWindowCounterRateLimiter(int limit, Duration window) {
        this(limit, window, DEFAULT_BUCKETS);
    }

    /**
     * 创建滑动窗口计数限流器。
     *
     * @param limit 窗口内允许放行的许可上限，必须为正数
     * @param window 窗口长度，必须为正且纳秒数能被 {@code buckets} 整除
     * @param buckets 子窗口数，必须为正数
     * @throws IllegalArgumentException 参数不合法时抛出
     * @throws NullPointerException 窗口为 {@code null} 时抛出
     */
    public SlidingWindowCounterRateLimiter(
            int limit, Duration window,
            int buckets) {
        this(limit, window, buckets, RateLimiters.SYSTEM_NANO_TIME);
    }

    /**
     * 使用指定时间源创建滑动窗口计数限流器，仅供同包测试注入假时钟。
     *
     * @param limit 窗口内允许放行的许可上限
     * @param window 窗口长度
     * @param buckets 子窗口数
     * @param nanoTimeSource 纳秒时间源
     */
    SlidingWindowCounterRateLimiter(
            int limit, Duration window,
            int buckets, LongSupplier nanoTimeSource) {
        this.limit = RateLimiters.requirePositive(limit, "limit");
        this.windowNanos = RateLimiters.toPositiveNanos(window);
        RateLimiters.requirePositive(buckets, "buckets");
        if (windowNanos % buckets != 0) {
            // 不整除时子窗口长度被截断，各子窗口拼起来短于窗口，会引入累计误差
            throw new IllegalArgumentException("窗口纳秒数 " + windowNanos + " 不能被子窗口数 " + buckets + " 整除");
        }
        this.bucketNanos = windowNanos / buckets;
        this.clock = RateLimiters.requireClock(nanoTimeSource);
        this.origin = clock.getAsLong();
        this.lastNanos = origin;
        this.bucketStarts = new long[buckets];
        this.counts = new long[buckets];
        // 初始起点设为创建前一个窗口，确保在首个窗口内不会被误判为有效格子（计数本身也为 0）
        for (int i = 0; i < buckets; i++) {
            bucketStarts[i] = origin - windowNanos;
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean tryAcquire(int permits) {
        RateLimiters.checkPermits(permits);
        if (permits > limit) {
            return false;
        }
        lastNanos = RateLimiters.monotonicNow(clock, lastNanos);
        long slot = (lastNanos - origin) / bucketNanos;
        long currentStart = origin + slot * bucketNanos;
        int index = (int) (slot % counts.length);
        if (bucketStarts[index] != currentStart) {
            // 格子仍记录着一个窗口之前的子窗口，清零后复用为当前子窗口
            bucketStarts[index] = currentStart;
            counts[index] = 0;
        }
        long sum = 0;
        for (int i = 0; i < counts.length; i++) {
            if (currentStart - bucketStarts[i] < windowNanos) {
                sum += counts[i];
            }
        }
        if (sum + permits > limit) {
            return false;
        }
        counts[index] += permits;
        return true;
    }

    /**
     * 返回窗口内的许可上限。
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
     * 返回子窗口数。
     *
     * @return 子窗口数
     */
    public int buckets() {
        return counts.length;
    }
}
