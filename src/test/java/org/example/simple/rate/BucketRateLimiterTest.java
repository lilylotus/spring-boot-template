package org.example.simple.rate;

import static org.example.simple.rate.RateLimiterContractTest.acquireAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 漏桶与令牌桶的算法行为测试。
 */
class BucketRateLimiterTest {

    /** 被测实现：名称与按"容量、速率、时间源"创建限流器的工厂。 */
    static Stream<Arguments> buckets() {
        return Stream.of(
                Arguments.of("LeakyBucket", (BucketFactory) LeakyBucketRateLimiter::new),
                Arguments.of("TokenBucket", (BucketFactory) TokenBucketRateLimiter::new));
    }

    /** 初始突发量等于容量，之后按速率恢复。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("buckets")
    void burstEqualsCapacityThenRecoversAtRate(String name, BucketFactory factory) {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = factory.create(5, 2.0, clock);
        assertEquals(5, acquireAll(limiter, 10));
        clock.advanceMillis(499);
        assertFalse(limiter.tryAcquire());
        clock.advanceMillis(1);
        assertEquals(1, acquireAll(limiter, 10));
        clock.advanceMillis(1000);
        assertEquals(2, acquireAll(limiter, 10));
        // 空闲再久，突发量也不超过容量
        clock.advanceMillis(60_000);
        assertEquals(5, acquireAll(limiter, 10));
    }

    /** 长期放行量不超过"容量 + 速率 × 时长"，且接近该上界。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("buckets")
    void longRunThroughputIsBoundedByRate(String name, BucketFactory factory) {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = factory.create(10, 50.0, clock);
        int granted = 0;
        // 10 秒内每 1 毫秒尝试 5 次
        for (int i = 0; i < 10_000; i++) {
            clock.advanceMillis(1);
            granted += acquireAll(limiter, 5);
        }
        assertTrue(granted <= 10 + 50 * 10, "放行 " + granted);
        assertTrue(granted >= 50 * 10, "放行 " + granted);
    }

    /** 小数速率：0.5/秒时 2 秒恢复 1 个许可。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("buckets")
    void fractionalRateGrantsOnTime(String name, BucketFactory factory) {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = factory.create(1, 0.5, clock);
        assertTrue(limiter.tryAcquire());
        clock.advanceMillis(1999);
        assertFalse(limiter.tryAcquire());
        clock.advanceMillis(1);
        assertTrue(limiter.tryAcquire());
    }

    /** 速率 0.1/秒、每 100 毫秒判断一次：浮点误差累加后仍在第 10 秒准时放行。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("buckets")
    void floatingPointAccumulationDoesNotDelayGrant(String name, BucketFactory factory) {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = factory.create(1, 0.1, clock);
        assertTrue(limiter.tryAcquire());
        for (int step = 1; step < 100; step++) {
            clock.advanceMillis(100);
            assertFalse(limiter.tryAcquire(), "第 " + step + " 步不应放行");
        }
        clock.advanceMillis(100);
        assertTrue(limiter.tryAcquire());
    }

    /** 水位法漏桶与初始满桶的令牌桶在数学上等价：任意相同请求序列的判断结果一致。 */
    @Test
    void leakyBucketIsEquivalentToFullTokenBucket() {
        FakeClock clock = new FakeClock();
        LeakyBucketRateLimiter leaky = new LeakyBucketRateLimiter(8, 3.0, clock);
        TokenBucketRateLimiter token = new TokenBucketRateLimiter(8, 3.0, clock);
        Random random = new Random(17L);
        for (int i = 0; i < 20_000; i++) {
            clock.advanceMillis(random.nextInt(200));
            int permits = 1 + random.nextInt(4);
            assertEquals(token.tryAcquire(permits), leaky.tryAcquire(permits), "第 " + i + " 次判断不一致");
        }
    }

    /** 非法构造参数抛出 IllegalArgumentException。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("buckets")
    void rejectsIllegalConstructorArguments(String name, BucketFactory factory) {
        FakeClock clock = new FakeClock();
        assertThrows(IllegalArgumentException.class, () -> factory.create(0, 1.0, clock));
        assertThrows(IllegalArgumentException.class, () -> factory.create(1, 0.0, clock));
        assertThrows(IllegalArgumentException.class, () -> factory.create(1, -1.0, clock));
        assertThrows(IllegalArgumentException.class, () -> factory.create(1, Double.NaN, clock));
        assertThrows(IllegalArgumentException.class, () -> factory.create(1, Double.POSITIVE_INFINITY, clock));
    }

    /** 访问器返回构造参数。 */
    @Test
    void accessorsReturnConfiguration() {
        LeakyBucketRateLimiter leaky = new LeakyBucketRateLimiter(3, 1.5);
        TokenBucketRateLimiter token = new TokenBucketRateLimiter(4, 2.5);
        assertEquals(3, leaky.capacity());
        assertEquals(1.5, leaky.leakRatePerSecond());
        assertEquals(4, token.capacity());
        assertEquals(2.5, token.refillRatePerSecond());
    }

    /** 真实时钟冒烟测试：默认构造器可用，短时间内放行数约等于容量。 */
    @Test
    void worksWithSystemClock() {
        RateLimiter[] limiters = {new LeakyBucketRateLimiter(100, 0.001), new TokenBucketRateLimiter(100, 0.001)};
        for (RateLimiter limiter : limiters) {
            assertEquals(100, acquireAll(limiter, 300), limiter.getClass().getSimpleName());
        }
    }

    /**
     * 按容量、速率与时间源创建桶类限流器的工厂。
     */
    @FunctionalInterface
    interface BucketFactory {

        /**
         * 创建限流器。
         *
         * @param capacity 容量
         * @param ratePerSecond 每秒速率
         * @param clock 时间源
         * @return 限流器
         */
        RateLimiter create(int capacity, double ratePerSecond, LongSupplier clock);
    }
}
