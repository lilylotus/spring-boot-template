package org.example.simple.rpc.transport;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 框架固定线程工厂与有界执行器。 */
public final class RpcExecutors {
    private RpcExecutors() {}

    public static ThreadFactory factory(String role) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "rpc-" + role + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static ThreadPoolExecutor pool(String role, int threads, int capacity) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                factory(role),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 为排队任务绑定取消清理，shutdownNow 返回的任务同样释放报文与准入资源。 */
    public static final class Lease implements Runnable {
        private final AtomicInteger references = new AtomicInteger(1);
        private final Runnable release;

        public Lease(Runnable release) {
            this.release = release;
        }

        public void retain() {
            references.incrementAndGet();
        }

        public void run() {
            if (references.decrementAndGet() == 0) {
                release.run();
            }
        }
    }

    public static final class Task implements Runnable {
        private final Runnable action;
        private final Runnable cleanup;
        private final java.util.concurrent.atomic.AtomicBoolean claimed =
                new java.util.concurrent.atomic.AtomicBoolean();

        public Task(Runnable action, Runnable cleanup) {
            this.action = action;
            this.cleanup = cleanup;
        }

        public void run() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            try {
                action.run();
            } finally {
                cleanup.run();
            }
        }

        public void discard() {
            if (claimed.compareAndSet(false, true)) {
                cleanup.run();
            }
        }
    }

    public static void stop(ThreadPoolExecutor executor) {
        for (Runnable task : executor.shutdownNow()) {
            if (task instanceof Task owned) {
                owned.discard();
            } else {
                task.run();
            }
        }
    }

    public static void requireExternalThread() {
        if (Thread.currentThread().getName().startsWith("rpc-")) {
            throw new IllegalStateException("框架线程不能执行阻塞操作，请使用异步入口");
        }
    }
}
