package org.example.simple.util;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 雪花生成器的边界、异常恢复与并发唯一性测试。 */
class SnowflakeUtilsTest {

    /** 避开纪元边界的固定测试时间。 */
    private static final long NOW = SnowflakeUtils.EPOCH_MILLIS + 1000;

    @Test
    void initializesStaticFacadeWithoutLeakingStateBetweenTests() throws Exception {
        URL location = SnowflakeUtils.class.getProtectionDomain().getCodeSource().getLocation();
        // 隔离静态生命周期，不通过反射重置生产状态，也不依赖测试执行顺序。
        try (URLClassLoader loader = new URLClassLoader(new URL[]{location}, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = loader.loadClass(SnowflakeUtils.class.getName());
            var next = type.getMethod("nextId");
            var initialize = type.getMethod("initialize", int.class, int.class);
            var invalid = assertThrows(InvocationTargetException.class, () -> initialize.invoke(null, -1, 0));
            assertInstanceOf(IllegalArgumentException.class, invalid.getCause());
            initialize.invoke(null, 31, 0);
            long first = (long) next.invoke(null);
            initialize.invoke(null, 31, 0);
            long second = (long) next.invoke(null);
            assertTrue(second > first);
            assertEquals(31, (second >>> 17) & 31);
            var conflict = assertThrows(InvocationTargetException.class, () -> initialize.invoke(null, 0, 0));
            assertInstanceOf(IllegalStateException.class, conflict.getCause());
            assertEquals(31, ((long) next.invoke(null) >>> 17) & 31);
        }
    }

    @Test
    void initializesDefaultNodeOnConcurrentFirstCalls() throws Exception {
        URL location = SnowflakeUtils.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{location}, ClassLoader.getPlatformClassLoader());
             var executor = Executors.newFixedThreadPool(8)) {
            Class<?> type = loader.loadClass(SnowflakeUtils.class.getName());
            var next = type.getMethod("nextId");
            var initialize = type.getMethod("initialize", int.class, int.class);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    List<Long> ids = new ArrayList<>();
                    for (int index = 0; index < 1000; index++) {
                        long id = (long) next.invoke(null);
                        assertTrue(id > 0);
                        assertEquals(0, (id >>> 12) & 1023);
                        ids.add(id);
                    }
                    return ids;
                }));
            }
            start.countDown();
            Set<Long> ids = new HashSet<>();
            for (var future : futures) {
                ids.addAll(future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(8000, ids.size());
            long before = (long) next.invoke(null);
            initialize.invoke(null, 0, 0);
            assertTrue((long) next.invoke(null) > before);
            var conflict = assertThrows(InvocationTargetException.class, () -> initialize.invoke(null, 1, 1));
            assertInstanceOf(IllegalStateException.class, conflict.getCause());
            assertEquals(0, ((long) next.invoke(null) >>> 12) & 1023);
        }
    }

    @Test
    void resolvesExplicitAndDefaultInitializationRaceWithoutReplacingNode() throws Exception {
        URL location = SnowflakeUtils.class.getProtectionDomain().getCodeSource().getLocation();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int attempt = 0; attempt < 20; attempt++) {
                try (URLClassLoader loader = new URLClassLoader(
                        new URL[]{location}, ClassLoader.getPlatformClassLoader())) {
                    Class<?> type = loader.loadClass(SnowflakeUtils.class.getName());
                    var next = type.getMethod("nextId");
                    var initialize = type.getMethod("initialize", int.class, int.class);
                    CountDownLatch start = new CountDownLatch(1);
                    Future<Long> generated = executor.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return (long) next.invoke(null);
                    });
                    Future<Boolean> configured = executor.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        try {
                            initialize.invoke(null, 2, 3);
                            return true;
                        } catch (InvocationTargetException exception) {
                            assertInstanceOf(IllegalStateException.class, exception.getCause());
                            return false;
                        }
                    });
                    start.countDown();
                    long first = generated.get(10, TimeUnit.SECONDS);
                    boolean explicitWon = configured.get(10, TimeUnit.SECONDS);
                    assertEquals(explicitWon ? 2 : 0, (first >>> 17) & 31);
                    assertEquals(explicitWon ? 3 : 0, (first >>> 12) & 31);
                    long second = (long) next.invoke(null);
                    assertTrue(second > first);
                    assertEquals((first >>> 12) & 1023, (second >>> 12) & 1023);
                }
            }
        }
    }

    @Test
    void validatesBothNodeRanges() {
        for (int invalid : new int[]{-1, 32}) {
            assertThrows(IllegalArgumentException.class,
                () -> new SnowflakeUtils.Generator(invalid, 0, () -> NOW));
            assertThrows(IllegalArgumentException.class,
                () -> new SnowflakeUtils.Generator(0, invalid, () -> NOW));
        }
    }

    @Test
    void exhaustsAllSequencesAndAdvancesToNextMillisecond() {
        AtomicInteger reads = new AtomicInteger();
        var generator = new SnowflakeUtils.Generator(3, 31,
            () -> reads.incrementAndGet() <= 4097 ? NOW : NOW + 1);
        long previous = 0;
        for (int sequence = 0; sequence < 4096; sequence++) {
            long id = generator.nextId();
            assertTrue(id > previous);
            assertEquals(1000, id >>> 22);
            assertEquals(3, (id >>> 17) & 31);
            assertEquals(31, (id >>> 12) & 31);
            assertEquals(sequence, id & 4095);
            previous = id;
        }
        long id = generator.nextId();
        assertEquals(1001, id >>> 22);
        assertEquals(0, id & 4095);
        assertTrue(id > previous);
    }

    @Test
    void handlesEpochAndMaximumTimestampWithoutReturningZeroOrOverflow() {
        AtomicLong clock = new AtomicLong(SnowflakeUtils.EPOCH_MILLIS);
        var generator = new SnowflakeUtils.Generator(0, 0, clock::get);
        assertEquals(1, generator.nextId());
        assertEquals(2, generator.nextId());
        clock.set(SnowflakeUtils.EPOCH_MILLIS + (1L << 41) - 1);
        var maximum = new SnowflakeUtils.Generator(31, 31, clock::get);
        long last = 0;
        for (int index = 0; index < 4096; index++) {
            last = maximum.nextId();
        }
        assertEquals(Long.MAX_VALUE, last);
        clock.incrementAndGet();
        assertThrows(IllegalStateException.class, maximum::nextId);
        clock.set(SnowflakeUtils.EPOCH_MILLIS - 1);
        assertThrows(IllegalStateException.class, generator::nextId);
    }

    @Test
    void recoversSmallRollbackAndRejectsLargeRollbackWithoutLosingState() {
        AtomicInteger reads = new AtomicInteger();
        var small = new SnowflakeUtils.Generator(0, 1,
            () -> reads.incrementAndGet() == 2 ? NOW - 5 : NOW);
        long first = small.nextId();
        assertEquals(first + 1, small.nextId());

        AtomicLong clock = new AtomicLong(NOW);
        var large = new SnowflakeUtils.Generator(0, 1, clock::get);
        long before = large.nextId();
        clock.set(NOW - 6);
        assertTrue(assertThrows(IllegalStateException.class, large::nextId).getMessage().contains("6"));
        clock.set(NOW);
        assertEquals(before + 1, large.nextId());
    }

    @Test
    void timesOutOnFrozenClockAndRetainsSequenceForRetry() {
        AtomicLong clock = new AtomicLong(NOW);
        var generator = new SnowflakeUtils.Generator(1, 1, clock::get);
        for (int index = 0; index < 4096; index++) {
            generator.nextId();
        }
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertTrue(assertThrows(IllegalStateException.class, generator::nextId).getMessage().contains("超时"));
            assertThrows(IllegalStateException.class, generator::nextId);
        });
        clock.incrementAndGet();
        assertEquals(0, generator.nextId() & 4095);
    }

    @Test
    void preservesInterruptDuringRollbackAndSequenceWaits() {
        for (boolean exhausted : new boolean[]{false, true}) {
            AtomicLong clock = new AtomicLong(NOW);
            var generator = new SnowflakeUtils.Generator(1, 1, clock::get);
            int count = exhausted ? 4096 : 1;
            for (int index = 0; index < count; index++) {
                generator.nextId();
            }
            if (!exhausted) {
                clock.decrementAndGet();
            }
            Thread.currentThread().interrupt();
            try {
                assertTrue(assertThrows(IllegalStateException.class, generator::nextId)
                    .getMessage().contains("中断"));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            clock.set(NOW + 1);
            assertEquals(0, generator.nextId() & 4095);
        }
    }

    @Test
    void generatesUniqueIdsUnderConcurrentLoad() throws Exception {
        AtomicLong ticks = new AtomicLong();
        var generator = new SnowflakeUtils.Generator(7, 9, () -> NOW + ticks.getAndIncrement() / 1000);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                futures.add(executor.submit(() -> {
                    List<Long> ids = new ArrayList<>();
                    long previous = 0;
                    for (int index = 0; index < 10000; index++) {
                        long id = generator.nextId();
                        assertTrue(id > previous);
                        assertEquals(7, (id >>> 17) & 31);
                        assertEquals(9, (id >>> 12) & 31);
                        ids.add(id);
                        previous = id;
                    }
                    return ids;
                }));
            }
            Set<Long> unique = new HashSet<>();
            for (Future<List<Long>> future : futures) {
                unique.addAll(future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(80000, unique.size());
        }
    }
}
