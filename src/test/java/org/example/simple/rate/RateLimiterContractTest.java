package org.example.simple.rate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 五种限流器的通用契约测试：参数校验、上限、失败无副作用、恢复、时间回退与回绕、并发安全。
 * <p>
 * 工厂按"上限或容量"与时间源创建限流器：窗口类窗口长度为 1 秒，桶类速率为每秒 1 个许可。
 */
class RateLimiterContractTest {

    /** 窗口类限流器统一使用的窗口长度。 */
    private static final Duration WINDOW = Duration.ofSeconds(1);

    /** 被测实现：名称与工厂。 */
    static Stream<Arguments> limiters() {
        return Stream.of(
                Arguments.of("FixedWindow", factory((n, c) -> new FixedWindowRateLimiter(n, WINDOW, c))),
                Arguments.of("SlidingWindowLog", factory((n, c) -> new SlidingWindowLogRateLimiter(n, WINDOW, c))),
                Arguments.of("SlidingWindowCounter",
                        factory((n, c) -> new SlidingWindowCounterRateLimiter(n, WINDOW, 10, c))),
                Arguments.of("LeakyBucket", factory((n, c) -> new LeakyBucketRateLimiter(n, 1.0, c))),
                Arguments.of("TokenBucket", factory((n, c) -> new TokenBucketRateLimiter(n, 1.0, c))));
    }

    /** 时间固定时恰好放行上限个许可。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void grantsExactlyLimitWhileTimeStandsStill(String name, BiFunction<Integer, LongSupplier, RateLimiter> factory) {
        RateLimiter limiter = factory.apply(5, new FakeClock());
        assertEquals(5, acquireAll(limiter, 20));
    }

    /** 非正许可数抛出 IllegalArgumentException。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void rejectsNonPositivePermits(String name, BiFunction<Integer, LongSupplier, RateLimiter> factory) {
        RateLimiter limiter = factory.apply(5, new FakeClock());
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(-1));
    }

    /** 许可数超过上限返回 false，且不影响后续放行。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void permitsAboveLimitAreRejectedWithoutSideEffect(
            String name, BiFunction<Integer, LongSupplier, RateLimiter> factory) {
        RateLimiter limiter = factory.apply(5, new FakeClock());
        assertFalse(limiter.tryAcquire(6));
        assertFalse(limiter.tryAcquire(Integer.MAX_VALUE));
        assertTrue(limiter.tryAcquire(5));
        assertFalse(limiter.tryAcquire());
    }

    /** 许可不足导致的失败不产生副作用，较小的请求仍按原状态判断。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void failedAcquireHasNoSideEffect(String name, BiFunction<Integer, LongSupplier, RateLimiter> factory) {
        RateLimiter limiter = factory.apply(5, new FakeClock());
        assertTrue(limiter.tryAcquire(3));
        assertFalse(limiter.tryAcquire(3));
        assertFalse(limiter.tryAcquire(3));
        assertTrue(limiter.tryAcquire(2));
        assertFalse(limiter.tryAcquire());
    }

    /** 长时间空闲后恢复，但不会超过上限。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void recoversAfterLongIdle(String name, BiFunction<Integer, LongSupplier, RateLimiter> factory) {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = factory.apply(5, clock);
        assertEquals(5, acquireAll(limiter, 10));
        clock.advanceMillis(TimeUnit.HOURS.toMillis(1));
        assertEquals(5, acquireAll(limiter, 10));
    }

    /** 时间源回退时不抛异常，也不会因此多放行。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void toleratesClockGoingBackwards(String name, BiFunction<Integer, LongSupplier, RateLimiter> factory) {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = factory.apply(5, clock);
        clock.advanceMillis(500);
        assertEquals(5, acquireAll(limiter, 10));
        clock.advanceMillis(-400);
        assertEquals(0, acquireAll(limiter, 10));
        clock.advanceMillis(400);
        assertEquals(0, acquireAll(limiter, 10));
    }

    /** 纳秒读数跨越 Long.MAX_VALUE 回绕时行为不变。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void handlesNanoTimeOverflow(String name, BiFunction<Integer, LongSupplier, RateLimiter> factory) {
        FakeClock clock = new FakeClock(Long.MAX_VALUE - 500 * FakeClock.NANOS_PER_MILLI);
        RateLimiter limiter = factory.apply(5, clock);
        assertEquals(5, acquireAll(limiter, 10));
        // 推进 300 毫秒，仍未回绕
        clock.advanceMillis(300);
        int beforeWrap = acquireAll(limiter, 10);
        // 再推进 1 小时，读数已回绕为负数
        clock.advanceMillis(TimeUnit.HOURS.toMillis(1));
        assertTrue(clock.getAsLong() < 0);
        assertEquals(5, acquireAll(limiter, 10));
        assertTrue(beforeWrap <= 1, "未满一个窗口或一个补充周期，最多恢复 1 个许可：" + beforeWrap);
    }

    /** 时间固定时多线程争抢，放行总数恰好等于上限。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void concurrentAcquireNeverExceedsLimit(String name, BiFunction<Integer, LongSupplier, RateLimiter> factory)
            throws Exception {
        int limit = 500;
        int threads = 8;
        int attemptsPerThread = 1000;
        RateLimiter limiter = factory.apply(limit, new FakeClock());
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return acquireAll(limiter, attemptsPerThread);
                }));
            }
            start.countDown();
            int granted = 0;
            for (Future<Integer> future : futures) {
                granted += future.get(30, TimeUnit.SECONDS);
            }
            assertEquals(limit, granted);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 连续尝试获取单个许可，返回成功次数。
     *
     * @param limiter 限流器
     * @param attempts 尝试次数
     * @return 成功次数
     */
    static int acquireAll(RateLimiter limiter, int attempts) {
        int granted = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire()) {
                granted++;
            }
        }
        return granted;
    }

    /** 为 lambda 指定工厂的目标类型。 */
    private static BiFunction<Integer, LongSupplier, RateLimiter> factory(
            BiFunction<Integer, LongSupplier, RateLimiter> factory) {
        return factory;
    }
}
