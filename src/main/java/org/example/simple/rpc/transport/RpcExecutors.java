package org.example.simple.rpc.transport;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 框架固定线程工厂与有界执行器。 */
public final class RpcExecutors {
    /** 工具类，禁止实例化。 */
    private RpcExecutors() {}

    /**
     * 创建按角色命名的守护线程工厂。
     *
     * <p>线程名统一为 {@code rpc-角色-序号}：便于线程栈排查定位，也是
     * {@link #requireExternalThread()} 判断当前是否为框架线程的依据。
     * 设为守护线程可避免框架线程阻止 JVM 退出。
     *
     * @param role 线程角色，如 boss、worker、business
     * @return 线程工厂
     */
    public static ThreadFactory factory(String role) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "rpc-" + role + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 创建固定线程数、有界队列、直接拒绝的线程池。
     *
     * <p>核心线程数与最大线程数相同且队列有界，任务满时立即以
     * {@link ThreadPoolExecutor.AbortPolicy} 拒绝，让过载以明确的失败快速暴露，
     * 而不是靠无界队列把压力堆积成内存问题。
     *
     * @param role 线程角色，决定线程名前缀
     * @param threads 固定线程数
     * @param capacity 等待队列容量
     * @return 线程池
     */
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
        /** 引用计数，初始为 1；归零时执行释放动作。 */
        private final AtomicInteger references = new AtomicInteger(1);
        /** 引用归零时执行的资源释放动作。 */
        private final Runnable release;

        /**
         * 创建引用计数租约。
         *
         * @param release 引用归零时执行的释放动作
         */
        public Lease(Runnable release) {
            this.release = release;
        }

        /** 增加一次引用，供新的持有者登记。 */
        public void retain() {
            references.incrementAndGet();
        }

        /** 释放一次引用，归零时执行释放动作。 */
        public void run() {
            if (references.decrementAndGet() == 0) {
                release.run();
            }
        }
    }

    /**
     * 可被安全丢弃的任务包装：无论正常执行还是被丢弃，清理动作都恰好执行一次。
     *
     * <p>用于线程池关闭时处理仍在队列中的任务，保证报文字节与准入许可不会因任务未执行而泄漏。
     */
    public static final class Task implements Runnable {
        /** 实际执行的业务动作。 */
        private final Runnable action;
        /** 无论执行还是丢弃都必须执行一次的清理动作。 */
        private final Runnable cleanup;
        /** 归属标记，保证执行与丢弃二者只发生一次。 */
        private final java.util.concurrent.atomic.AtomicBoolean claimed =
                new java.util.concurrent.atomic.AtomicBoolean();

        /**
         * 创建可丢弃任务。
         *
         * @param action 业务动作
         * @param cleanup 清理动作
         */
        public Task(Runnable action, Runnable cleanup) {
            this.action = action;
            this.cleanup = cleanup;
        }

        /** 执行业务动作，结束后执行清理；已被丢弃的任务直接返回。 */
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

        /** 丢弃任务：不执行业务动作，只执行清理动作，用于线程池关闭时处理残留队列任务。 */
        public void discard() {
            if (claimed.compareAndSet(false, true)) {
                cleanup.run();
            }
        }
    }

    /**
     * 立即停止线程池，并处理仍在队列中的任务。
     *
     * <p>{@link Task} 走丢弃路径只做资源清理；其它任务（如租约释放）直接执行，
     * 确保停机时不残留未归还的报文字节或准入许可。
     *
     * @param executor 待停止的线程池
     */
    public static void stop(ThreadPoolExecutor executor) {
        for (Runnable task : executor.shutdownNow()) {
            if (task instanceof Task owned) {
                owned.discard();
            } else {
                task.run();
            }
        }
    }

    /**
     * 断言当前线程不是框架线程。
     *
     * <p>用于保护 connect、close 等会阻塞等待的入口：在 I/O 或业务线程上阻塞等待框架自身的结果
     * 会造成死锁，因此这里直接以异常暴露误用。
     *
     * @throws IllegalStateException 当前线程为框架线程时抛出
     */
    public static void requireExternalThread() {
        if (Thread.currentThread().getName().startsWith("rpc-")) {
            throw new IllegalStateException("框架线程不能执行阻塞操作，请使用异步入口");
        }
    }
}
