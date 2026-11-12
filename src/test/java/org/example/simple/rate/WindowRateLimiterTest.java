package org.example.simple.rate;

import static org.example.simple.rate.RateLimiterContractTest.acquireAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 固定窗口计数、滑动窗口（日志法）与滑动窗口计数三种窗口类限流器的算法行为测试。
 */
class WindowRateLimiterTest {

    /** 测试统一使用的 1 秒窗口。 */
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    /** 固定窗口：窗口内达到上限后拒绝，进入下一窗口恢复。 */
    @Test
    void fixedWindowResetsAtWindowBoundary() {
        FakeClock clock = new FakeClock();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(3, ONE_SECOND, clock);
        assertEquals(3, acquireAll(limiter, 5));
        clock.advanceMillis(999);
        assertFalse(limiter.tryAcquire());
        clock.advanceMillis(1);
        assertEquals(3, acquireAll(limiter, 5));
        assertEquals(3, limiter.limit());
        assertEquals(ONE_SECOND, limiter.window());
    }

    /** 固定窗口：长时间空闲后窗口仍与创建时刻对齐。 */
    @Test
    void fixedWindowStaysAlignedAfterIdle() {
        FakeClock clock = new FakeClock();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, ONE_SECOND, clock);
        clock.advanceMillis(10_500);
        assertEquals(2, acquireAll(limiter, 5));
        // 所在窗口为 [10s, 11s)，在 10.999 秒仍处于同一窗口
        clock.advanceMillis(499);
        assertFalse(limiter.tryAcquire());
        clock.advanceMillis(1);
        assertEquals(2, acquireAll(limiter, 5));
    }

    /** 固定窗口存在边界突发：窗口边界两侧 1 毫秒内放行 2 倍上限；滑动日志同场景只放行上限。 */
    @Test
    void fixedWindowAllowsBoundaryBurstWhileSlidingLogDoesNot() {
        FakeClock clock = new FakeClock();
        FixedWindowRateLimiter fixed = new FixedWindowRateLimiter(10, ONE_SECOND, clock);
        SlidingWindowLogRateLimiter log = new SlidingWindowLogRateLimiter(10, ONE_SECOND, clock);
        clock.advanceMillis(999);
        assertTrue(fixed.tryAcquire(10));
        assertTrue(log.tryAcquire(10));
        clock.advanceMillis(1);
        assertTrue(fixed.tryAcquire(10));
        assertFalse(log.tryAcquire());
    }

    /** 滑动日志：许可距今恰好满一个窗口时滑出，窗口为左开右闭区间。 */
    @Test
    void slidingLogExpiresGrantsExactlyAfterWindow() {
        FakeClock clock = new FakeClock();
        SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(4, ONE_SECOND, clock);
        assertTrue(limiter.tryAcquire(2));
        clock.advanceMillis(400);
        assertTrue(limiter.tryAcquire(2));
        clock.advanceNanos(600 * FakeClock.NANOS_PER_MILLI - 1);
        assertFalse(limiter.tryAcquire());
        // 第一条记录距今恰好 1 秒，滑出窗口，释放 2 个许可
        clock.advanceNanos(1);
        assertTrue(limiter.tryAcquire(2));
        assertFalse(limiter.tryAcquire());
        clock.advanceMillis(400);
        assertTrue(limiter.tryAcquire(2));
        assertEquals(4, limiter.limit());
        assertEquals(ONE_SECOND, limiter.window());
    }

    /** 滑动日志：随机时序下任意窗口内放行数都不超过上限。 */
    @Test
    void slidingLogNeverExceedsLimitInAnyWindow() {
        FakeClock clock = new FakeClock(0);
        SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(20, ONE_SECOND, clock);
        Random random = new Random(42L);
        long[] grantTimes = new long[100_000];
        int granted = 0;
        for (int i = 0; i < 20_000; i++) {
            clock.advanceMillis(random.nextInt(30));
            int permits = 1 + random.nextInt(3);
            if (limiter.tryAcquire(permits)) {
                for (int p = 0; p < permits; p++) {
                    grantTimes[granted++] = clock.getAsLong();
                }
            }
        }
        // 每个放行时刻往前 1 秒（左开）内的放行数都不超过上限
        int left = 0;
        for (int right = 0; right < granted; right++) {
            while (grantTimes[right] - grantTimes[left] >= ONE_SECOND.toNanos()) {
                left++;
            }
            assertTrue(right - left + 1 <= 20, "窗口内放行数超过上限");
        }
        assertTrue(granted > 0);
    }

    /** 滑动窗口计数：子窗口在整个窗口过去后移出统计。 */
    @Test
    void slidingCounterDropsExpiredBuckets() {
        FakeClock clock = new FakeClock();
        SlidingWindowCounterRateLimiter limiter = new SlidingWindowCounterRateLimiter(10, ONE_SECOND, 10, clock);
        assertTrue(limiter.tryAcquire(5));
        clock.advanceMillis(250);
        assertTrue(limiter.tryAcquire(5));
        clock.advanceMillis(749);
        assertFalse(limiter.tryAcquire());
        // 进入第 10 个子窗口 [1000ms, 1100ms)：第 0 个子窗口的 5 个许可移出，第 2 个子窗口的 5 个仍计入
        clock.advanceMillis(1);
        assertEquals(5, acquireAll(limiter, 10));
        // 进入第 12 个子窗口 [1200ms, 1300ms)：第 2 个子窗口移出，仅剩第 10 个子窗口的 5 个
        clock.advanceMillis(200);
        assertEquals(5, acquireAll(limiter, 10));
        assertEquals(10, limiter.buckets());
        assertEquals(ONE_SECOND, limiter.window());
        assertEquals(10, limiter.limit());
    }

    /** 滑动窗口计数：子窗口数为 1 时行为与固定窗口完全一致。 */
    @Test
    void slidingCounterWithSingleBucketBehavesLikeFixedWindow() {
        FakeClock clock = new FakeClock();
        SlidingWindowCounterRateLimiter counter = new SlidingWindowCounterRateLimiter(7, ONE_SECOND, 1, clock);
        FixedWindowRateLimiter fixed = new FixedWindowRateLimiter(7, ONE_SECOND, clock);
        Random random = new Random(9L);
        for (int i = 0; i < 20_000; i++) {
            clock.advanceMillis(random.nextInt(120));
            int permits = 1 + random.nextInt(4);
            assertEquals(fixed.tryAcquire(permits), counter.tryAcquire(permits), "第 " + i + " 次判断不一致");
        }
    }

    /** 滑动窗口计数：任意窗口内放行数不超过上限加一个子窗口的误差。 */
    @Test
    void slidingCounterErrorIsBoundedByOneBucket() {
        FakeClock clock = new FakeClock(0);
        int limit = 50;
        SlidingWindowCounterRateLimiter limiter = new SlidingWindowCounterRateLimiter(limit, ONE_SECOND, 10, clock);
        Random random = new Random(5L);
        long[] grantTimes = new long[100_000];
        int granted = 0;
        for (int i = 0; i < 30_000; i++) {
            clock.advanceMillis(random.nextInt(20));
            if (limiter.tryAcquire()) {
                grantTimes[granted++] = clock.getAsLong();
            }
        }
        // 统计范围是"当前子窗口 + 前 9 个完整子窗口"，真实 1 秒窗口与之相比最多多覆盖一个子窗口
        int left = 0;
        int maxInWindow = 0;
        for (int right = 0; right < granted; right++) {
            while (grantTimes[right] - grantTimes[left] >= ONE_SECOND.toNanos()) {
                left++;
            }
            maxInWindow = Math.max(maxInWindow, right - left + 1);
        }
        assertTrue(maxInWindow <= limit * 2, "超出误差上界：" + maxInWindow);
        assertTrue(maxInWindow >= limit);
    }

    /** 默认子窗口数为 10。 */
    @Test
    void slidingCounterUsesDefaultBuckets() {
        SlidingWindowCounterRateLimiter limiter = new SlidingWindowCounterRateLimiter(5, ONE_SECOND);
        assertEquals(SlidingWindowCounterRateLimiter.DEFAULT_BUCKETS, limiter.buckets());
    }

    /** 三种窗口类限流器的非法构造参数。 */
    @Test
    void rejectsIllegalConstructorArguments() {
        assertThrows(IllegalArgumentException.class, () -> new FixedWindowRateLimiter(0, ONE_SECOND));
        assertThrows(IllegalArgumentException.class, () -> new FixedWindowRateLimiter(1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new FixedWindowRateLimiter(1, Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> new FixedWindowRateLimiter(1, null));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLogRateLimiter(-1, ONE_SECOND));
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowLogRateLimiter(1, Duration.ofSeconds(Long.MAX_VALUE)));
        assertThrows(NullPointerException.class, () -> new SlidingWindowLogRateLimiter(1, null));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowCounterRateLimiter(0, ONE_SECOND));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowCounterRateLimiter(1, ONE_SECOND, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCounterRateLimiter(1, Duration.ofNanos(1001), 10));
        assertThrows(NullPointerException.class, () -> new SlidingWindowCounterRateLimiter(1, null, 10));
    }

    /** 真实时钟冒烟测试：默认构造器可用，短时间内放行数不超过上限。 */
    @Test
    void worksWithSystemClock() {
        Duration minute = Duration.ofMinutes(1);
        RateLimiter[] limiters = {
            new FixedWindowRateLimiter(100, minute),
            new SlidingWindowLogRateLimiter(100, minute),
            new SlidingWindowCounterRateLimiter(100, minute)
        };
        for (RateLimiter limiter : limiters) {
            int granted = acquireAll(limiter, 300);
            // 固定窗口可能恰好跨越窗口边界，最多放行 2 倍上限
            int max = limiter instanceof FixedWindowRateLimiter ? 200 : 100;
            assertTrue(granted >= 100 && granted <= max, limiter.getClass().getSimpleName() + " 放行 " + granted);
        }
    }
}
