package org.example.simple.rate;

/**
 * 限流器：按算法规则判断当前是否允许放行指定数量的许可。
 * <p>
 * 实现类：
 * <ul>
 *   <li>{@link FixedWindowRateLimiter}：固定窗口计数，实现最简单，窗口边界存在 2 倍突发问题；</li>
 *   <li>{@link SlidingWindowLogRateLimiter}：滑动窗口（日志法），结果精确，内存与上限成正比；</li>
 *   <li>{@link SlidingWindowCounterRateLimiter}：滑动窗口计数，以子窗口近似滑动窗口，内存固定；</li>
 *   <li>{@link LeakyBucketRateLimiter}：漏桶（水位法），以恒定速率漏水限制流入；</li>
 *   <li>{@link TokenBucketRateLimiter}：令牌桶，以恒定速率补充令牌，容量决定允许的突发量。</li>
 * </ul>
 * <p>
 * 通用约定：
 * <ul>
 *   <li>{@code tryAcquire} 为非阻塞调用，立即返回是否放行，不等待；</li>
 *   <li>许可数不是正数时抛出 {@link IllegalArgumentException}；许可数超过窗口上限或桶容量时永远无法满足，
 *   直接返回 {@code false}；</li>
 *   <li>获取失败不产生任何副作用（不计数、不注水、不扣减令牌）；</li>
 *   <li>所有实现线程安全，时间基于单调时钟 {@link System#nanoTime()}，不受系统时间调整影响。</li>
 * </ul>
 */
public interface RateLimiter {

    /**
     * 尝试获取 1 个许可。
     *
     * @return 放行时返回 {@code true}
     */
    default boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * 尝试一次性获取指定数量的许可：要么全部获得，要么一个也不获得。
     *
     * @param permits 许可数，必须为正数
     * @return 放行时返回 {@code true}
     * @throws IllegalArgumentException 许可数不是正数时抛出
     */
    boolean tryAcquire(int permits);
}
