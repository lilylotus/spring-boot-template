package org.example.simple.rpc.transport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端级报文字节预算测试。
 * <p>
 * 覆盖准入上限、非法字节数拒绝、释放后容量恢复，以及并发准入不会突破预算。
 */
class ByteBudgetTest {

    @Test
    void acquiresUpToLimitAndRejectsOverflow() {
        ByteBudget budget = new ByteBudget(100);

        assertTrue(budget.acquire(60));
        assertTrue(budget.acquire(40));
        assertFalse(budget.acquire(1));
        assertEquals(100, budget.used());
    }

    @Test
    void rejectsNegativeSizeWithoutChangingUsage() {
        ByteBudget budget = new ByteBudget(100);

        assertFalse(budget.acquire(-1));
        assertEquals(0, budget.used());
    }

    @Test
    void releaseRestoresCapacity() {
        ByteBudget budget = new ByteBudget(100);

        assertTrue(budget.acquire(100));
        assertFalse(budget.acquire(1));
        budget.release(40);

        assertEquals(60, budget.used());
        assertTrue(budget.acquire(40));
        assertEquals(100, budget.used());
    }

    @Test
    void concurrentAcquireNeverExceedsLimit() throws InterruptedException {
        int threads = 16;
        int perThread = 200;
        ByteBudget budget = new ByteBudget(threads * perThread / 2);
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int attempt = 0; attempt < perThread; attempt++) {
                        if (budget.acquire(1)) {
                            granted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS));

        assertEquals(threads * perThread / 2, granted.get());
        assertEquals(threads * perThread / 2, budget.used());
    }
}
