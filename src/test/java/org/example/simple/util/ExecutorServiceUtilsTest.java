package org.example.simple.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExecutorServiceUtils} 单元测试。
 */
class ExecutorServiceUtilsTest {

    @Test
    void executesRunnableAsynchronously() {
        CountDownLatch completed = new CountDownLatch(1);

        ExecutorServiceUtils.execute(completed::countDown);

        assertTimeoutPreemptively(
            Duration.ofSeconds(3),
            () -> assertTrue(completed.await(2, TimeUnit.SECONDS)));
    }

    @Test
    void submitsRunnableAndCallable() throws Exception {
        Future<?> runnableFuture = ExecutorServiceUtils.submit(() -> {
        });
        Future<String> callableFuture = ExecutorServiceUtils.submit(() -> "执行结果");

        runnableFuture.get(2, TimeUnit.SECONDS);
        assertTrue(runnableFuture.isDone());
        assertEquals("执行结果", callableFuture.get(2, TimeUnit.SECONDS));
    }

    @Test
    void exposesTaskFailureThroughFuture() {
        IllegalStateException failure = new IllegalStateException("任务执行失败");
        Future<?> future = ExecutorServiceUtils.submit(() -> {
            throw failure;
        });

        ExecutionException exception = assertThrows(ExecutionException.class, future::get);

        assertEquals(failure, exception.getCause());
    }

    @Test
    void rejectsNullTasks() {
        assertThrows(IllegalArgumentException.class, () -> ExecutorServiceUtils.execute(null));
        assertThrows(
            IllegalArgumentException.class,
            () -> ExecutorServiceUtils.submit((Runnable) null));
        assertThrows(
            IllegalArgumentException.class,
            () -> ExecutorServiceUtils.submit((Callable<Object>) null));
    }

    @Test
    void usesNamedDaemonThreads() throws Exception {
        ThreadDetails details = ExecutorServiceUtils.submit(() -> {
            Thread currentThread = Thread.currentThread();
            return new ThreadDetails(currentThread.getName(), currentThread.isDaemon());
        }).get(2, TimeUnit.SECONDS);

        assertTrue(details.name().startsWith("ExecutorServiceUtils-"));
        assertTrue(details.daemon());
    }

    @Test
    void rejectsTaskWhenWorkersAndQueueAreFull() throws Exception {
        CountDownLatch workersStarted = new CountDownLatch(ExecutorServiceUtils.DEFAULT_THREAD_COUNT);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        List<Future<?>> acceptedTasks = new ArrayList<>();
        try {
            for (int index = 0; index < ExecutorServiceUtils.DEFAULT_THREAD_COUNT; index++) {
                acceptedTasks.add(ExecutorServiceUtils.submit(() -> {
                    workersStarted.countDown();
                    awaitUninterruptibly(releaseWorkers);
                }));
            }
            assertTrue(workersStarted.await(2, TimeUnit.SECONDS));

            for (int index = 0; index < ExecutorServiceUtils.DEFAULT_QUEUE_CAPACITY; index++) {
                acceptedTasks.add(ExecutorServiceUtils.submit(() -> {
                }));
            }

            RejectedExecutionException exception = assertThrows(
                RejectedExecutionException.class,
                () -> ExecutorServiceUtils.submit(() -> "被拒绝"));

            assertTrue(exception.getMessage().contains("线程池任务已满"));
            assertTrue(exception.getMessage().contains("活动线程数=4"));
            assertTrue(exception.getMessage().contains(
                "当前排队任务数=" + ExecutorServiceUtils.DEFAULT_QUEUE_CAPACITY));
            assertTrue(exception.getMessage().contains(
                "最大队列容量=" + ExecutorServiceUtils.DEFAULT_QUEUE_CAPACITY));
        } finally {
            releaseWorkers.countDown();
            for (Future<?> future : acceptedTasks) {
                future.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void runsAtMostFourBlockingTasksConcurrently() throws Exception {
        CountDownLatch workersStarted = new CountDownLatch(ExecutorServiceUtils.DEFAULT_THREAD_COUNT);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        Future<?> queuedTask = null;
        List<Future<?>> runningTasks = new ArrayList<>();
        try {
            for (int index = 0; index < ExecutorServiceUtils.DEFAULT_THREAD_COUNT; index++) {
                runningTasks.add(ExecutorServiceUtils.submit(() -> {
                    workersStarted.countDown();
                    awaitUninterruptibly(releaseWorkers);
                }));
            }
            assertTrue(workersStarted.await(2, TimeUnit.SECONDS));

            queuedTask = ExecutorServiceUtils.submit(() -> {
            });

            assertFalse(queuedTask.isDone());
        } finally {
            releaseWorkers.countDown();
            for (Future<?> future : runningTasks) {
                future.get(3, TimeUnit.SECONDS);
            }
            if (queuedTask != null) {
                queuedTask.get(3, TimeUnit.SECONDS);
            }
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 任务执行线程的可观察属性。
     *
     * @param name 线程名称
     * @param daemon 是否为守护线程
     */
    private record ThreadDetails(String name, boolean daemon) {
    }
}
