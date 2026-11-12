package org.example.simple.rate;

import java.util.function.LongSupplier;

/**
 * 漏桶限流器（水位计量形态）：每个许可向桶内注入一单位水，桶以恒定速率漏水，注水后水位超过容量即拒绝。
 * <p>
 * 原理：保存当前水位与上次漏水时间，判断时先按经过时间惰性漏水 {@code water = max(0, water - elapsed·rate)}，
 * 再判断 {@code water + permits <= capacity}。初始为空桶。
 * <p>
 * 特性：空闲后可瞬时放行至多 {@code capacity} 个许可，之后长期放行速率不超过 {@code leakRatePerSecond}；
 * {@code capacity = 1} 时近似严格匀速，相邻放行间隔不小于 {@code 1 / leakRatePerSecond} 秒。
 * <p>
 * 与令牌桶的关系：水位计量形态的漏桶与"初始满桶"的 {@link TokenBucketRateLimiter} 在数学上等价
 * （水位 = 容量 - 令牌数）。二者区别在于视角——漏桶限制"流入量"，令牌桶发放"许可"——以及扩展方向：
 * 漏桶常扩展为"排队整形"形态（请求入队后以恒定间隔出队执行），以获得严格匀速的输出，本实现不包含该形态。
 * <p>
 * 复杂度：每次判断 O(1)，空间 O(1)。
 * <p>
 * 适用场景：保护下游处理能力恒定的系统，如按固定速率调用第三方接口、消息匀速投递。
 * <p>
 * 线程安全：所有判断与修改在对象锁内完成。
 */
public class LeakyBucketRateLimiter implements RateLimiter {

    /** 桶容量，即允许积压的最大水量。 */
    private final int capacity;

    /** 每秒漏出的水量。 */
    private final double leakRatePerSecond;

    /** 纳秒时间源。 */
    private final LongSupplier clock;

    /** 当前水位。 */
    private double water;

    /** 上次漏水时间（纳秒）。 */
    private long lastLeakNanos;

    /**
     * 创建漏桶限流器，初始为空桶。
     *
     * @param capacity 桶容量，必须为正数
     * @param leakRatePerSecond 每秒漏水量，必须为有限正数
     * @throws IllegalArgumentException 参数不合法时抛出
     */
    public LeakyBucketRateLimiter(int capacity, double leakRatePerSecond) {
        this(capacity, leakRatePerSecond, RateLimiters.SYSTEM_NANO_TIME);
    }

    /**
     * 使用指定时间源创建漏桶限流器，仅供同包测试注入假时钟。
     *
     * @param capacity 桶容量
     * @param leakRatePerSecond 每秒漏水量
     * @param nanoTimeSource 纳秒时间源
     */
    LeakyBucketRateLimiter(
            int capacity, double leakRatePerSecond,
            LongSupplier nanoTimeSource) {
        this.capacity = RateLimiters.requirePositive(capacity, "capacity");
        this.leakRatePerSecond = RateLimiters.requirePositiveRate(leakRatePerSecond, "leakRatePerSecond");
        this.clock = RateLimiters.requireClock(nanoTimeSource);
        this.lastLeakNanos = clock.getAsLong();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean tryAcquire(int permits) {
        RateLimiters.checkPermits(permits);
        if (permits > capacity) {
            return false;
        }
        long now = RateLimiters.monotonicNow(clock, lastLeakNanos);
        double leaked = (now - lastLeakNanos) * leakRatePerSecond / RateLimiters.NANOS_PER_SECOND;
        water = Math.max(0, water - leaked);
        lastLeakNanos = now;
        if (water + permits > capacity + RateLimiters.EPSILON) {
            return false;
        }
        water = Math.min(capacity, water + permits);
        return true;
    }

    /**
     * 返回桶容量。
     *
     * @return 桶容量
     */
    public int capacity() {
        return capacity;
    }

    /**
     * 返回每秒漏水量。
     *
     * @return 每秒漏水量
     */
    public double leakRatePerSecond() {
        return leakRatePerSecond;
    }
}
