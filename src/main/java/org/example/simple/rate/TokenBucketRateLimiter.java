package org.example.simple.rate;

import java.util.function.LongSupplier;

/**
 * 令牌桶限流器：以恒定速率向桶中补充令牌，每个许可消耗一个令牌，令牌不足即拒绝。
 * <p>
 * 原理：保存当前令牌数与上次补充时间，判断时先按经过时间惰性补充
 * {@code tokens = min(capacity, tokens + elapsed·rate)}，再判断 {@code tokens >= permits}，无需后台线程定时补充。
 * 初始为满桶。
 * <p>
 * 特性：长期放行速率不超过 {@code refillRatePerSecond}；空闲期间积累的令牌允许随后瞬时放行至多
 * {@code capacity} 个许可，因此既能限制平均速率，又能容忍短时突发。与 {@link LeakyBucketRateLimiter} 的关系见其说明。
 * <p>
 * 复杂度：每次判断 O(1)，空间 O(1)。
 * <p>
 * 适用场景：需要限制平均速率、同时允许一定突发的场景，如 API 网关限流、客户端请求节流；
 * Guava {@code RateLimiter} 与本项目 RPC 服务端限流均采用令牌桶思想。
 * <p>
 * 线程安全：所有判断与修改在对象锁内完成。
 */
public class TokenBucketRateLimiter implements RateLimiter {

    /** 桶容量，即最多可积累的令牌数。 */
    private final int capacity;

    /** 每秒补充的令牌数。 */
    private final double refillRatePerSecond;

    /** 纳秒时间源。 */
    private final LongSupplier clock;

    /** 当前令牌数。 */
    private double tokens;

    /** 上次补充时间（纳秒）。 */
    private long lastRefillNanos;

    /**
     * 创建令牌桶限流器，初始为满桶。
     *
     * @param capacity 桶容量，必须为正数
     * @param refillRatePerSecond 每秒补充的令牌数，必须为有限正数
     * @throws IllegalArgumentException 参数不合法时抛出
     */
    public TokenBucketRateLimiter(int capacity, double refillRatePerSecond) {
        this(capacity, refillRatePerSecond, RateLimiters.SYSTEM_NANO_TIME);
    }

    /**
     * 使用指定时间源创建令牌桶限流器，仅供同包测试注入假时钟。
     *
     * @param capacity 桶容量
     * @param refillRatePerSecond 每秒补充的令牌数
     * @param nanoTimeSource 纳秒时间源
     */
    TokenBucketRateLimiter(
            int capacity, double refillRatePerSecond,
            LongSupplier nanoTimeSource) {
        this.capacity = RateLimiters.requirePositive(capacity, "capacity");
        this.refillRatePerSecond = RateLimiters.requirePositiveRate(refillRatePerSecond, "refillRatePerSecond");
        this.clock = RateLimiters.requireClock(nanoTimeSource);
        this.tokens = capacity;
        this.lastRefillNanos = clock.getAsLong();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean tryAcquire(int permits) {
        RateLimiters.checkPermits(permits);
        if (permits > capacity) {
            return false;
        }
        long now = RateLimiters.monotonicNow(clock, lastRefillNanos);
        double refilled = (now - lastRefillNanos) * refillRatePerSecond / RateLimiters.NANOS_PER_SECOND;
        tokens = Math.min(capacity, tokens + refilled);
        lastRefillNanos = now;
        if (tokens + RateLimiters.EPSILON < permits) {
            return false;
        }
        tokens = Math.max(0, tokens - permits);
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
     * 返回每秒补充的令牌数。
     *
     * @return 每秒补充的令牌数
     */
    public double refillRatePerSecond() {
        return refillRatePerSecond;
    }
}
