package org.example.simple.rpc.transport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 框架线程工厂与有界执行器测试。
 * <p>
 * 覆盖固定线程数、有界队列、{@code AbortPolicy} 拒绝路径、框架线程保护，
 * 以及停机时排队工作项的资源释放。
 */
class RpcExecutorsTest {

    @Test
    void createsFixedSizeBoundedPool() {
        ThreadPoolExecutor pool = RpcExecutors.pool("test-fixed", 3, 7);
        try {
            assertEquals(3, pool.getCorePoolSize());
            assertEquals(3, pool.getMaximumPoolSize());
            assertEquals(7, pool.getQueue().remainingCapacity());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsTaskWhenThreadsAndQueueAreExhausted() throws InterruptedException {
        ThreadPoolExecutor pool = RpcExecutors.pool("test-reject", 1, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                running.countDown();
                await(release);
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            pool.execute(() -> { });

            assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void namesThreadsWithFrameworkPrefixAndMarksThemDaemon() {
        Thread thread = RpcExecutors.factory("codec").newThread(() -> { });

        assertTrue(thread.getName().startsWith("rpc-codec-"));
        assertTrue(thread.isDaemon());
    }

    @Test
    void requireExternalThreadRejectsFrameworkThreads() throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread framework = RpcExecutors.factory("completion").newThread(() -> {
            try {
                RpcExecutors.requireExternalThread();
            } catch (RuntimeException error) {
                failure.set(error);
            }
        });

        framework.start();
        framework.join(5000);

        assertInstanceOf(IllegalStateException.class, failure.get());
        RpcExecutors.requireExternalThread();
    }

    @Test
    void stopReleasesResourcesOfQueuedTasks() throws InterruptedException {
        ThreadPoolExecutor pool = RpcExecutors.pool("test-stop", 1, 4);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger();

        pool.execute(() -> {
            running.countDown();
            await(release);
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        pool.execute(new RpcExecutors.Task(() -> { }, released::incrementAndGet));

        RpcExecutors.stop(pool);

        assertEquals(1, released.get());
        release.countDown();
    }

    @Test
    void taskRunsCleanupExactlyOnceWhenExecutedAndDiscarded() {
        AtomicInteger cleaned = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        RpcExecutors.Task task = new RpcExecutors.Task(executed::incrementAndGet, cleaned::incrementAndGet);

        task.run();
        task.discard();
        task.run();

        assertEquals(1, executed.get());
        assertEquals(1, cleaned.get());
    }

    @Test
    void leaseReleasesOnlyAfterEveryHolderCompletes() {
        AtomicInteger released = new AtomicInteger();
        RpcExecutors.Lease lease = new RpcExecutors.Lease(released::incrementAndGet);

        lease.retain();
        lease.run();
        assertEquals(0, released.get());

        lease.run();
        assertEquals(1, released.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
