package org.example.simple.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多线程任务执行工具，内部共享固定大小的有界线程池。
 * <p>
 * 工具使用四个具名守护线程和容量为 1024 的等待队列。调用方不能获取或关闭内部执行器；需要关注任务结果或
 * 任务异常时，应使用 {@link #submit(Runnable)} 或 {@link #submit(Callable)} 返回的 {@link Future}。
 * 由于工作线程为守护线程，重要任务必须通过 {@code Future} 等待完成，不能依赖 JVM 退出时继续执行。
 */
public final class ExecutorServiceUtils {

    /** 默认工作线程数，最多允许四个任务同时执行。 */
    public static final int DEFAULT_THREAD_COUNT = 4;

    /** 默认等待队列容量，超过该数量且工作线程全部繁忙时拒绝新任务。 */
    public static final int DEFAULT_QUEUE_CAPACITY = 1024;

    /** 工作线程名称序号，确保线程转储中的名称唯一且便于定位。 */
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    /** 具名守护线程工厂，避免共享执行器单独阻止 JVM 正常退出。 */
    private static final ThreadFactory THREAD_FACTORY = task -> {
        Thread thread = new Thread(task, "ExecutorServiceUtils-" + THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };

    /**
     * 内部共享执行器，固定四个线程并使用容量为 1024 的公平性默认有界队列。
     * <p>
     * 执行线程和等待队列同时满载时，自定义拒绝策略会抛出包含实时状态的中文异常。
     */
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
        DEFAULT_THREAD_COUNT,
        DEFAULT_THREAD_COUNT,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY),
        THREAD_FACTORY,
        ExecutorServiceUtils::rejectTask);

    private ExecutorServiceUtils() {
    }

    /**
     * 异步执行无返回值任务。
     * <p>
     * 任务执行期间抛出的未捕获异常按照 {@link ThreadPoolExecutor#execute(Runnable)} 的语义交由工作线程处理。
     * 如需通过 {@code Future} 观察异常，请使用 {@link #submit(Runnable)}。
     *
     * @param task 待执行任务，不能为 {@code null}
     * @throws IllegalArgumentException 任务为 {@code null} 时抛出
     * @throws RejectedExecutionException 工作线程和等待队列均已满时抛出
     */
    public static void execute(Runnable task) {
        EXECUTOR.execute(requireTask(task));
    }

    /**
     * 提交无返回值任务并返回任务状态句柄。
     *
     * @param task 待执行任务，不能为 {@code null}
     * @return 可用于等待完成、取消任务或观察异常的 {@code Future}
     * @throws IllegalArgumentException 任务为 {@code null} 时抛出
     * @throws RejectedExecutionException 工作线程和等待队列均已满时抛出
     */
    public static Future<?> submit(Runnable task) {
        return EXECUTOR.submit(requireTask(task));
    }

    /**
     * 提交具有返回值的任务并返回结果句柄。
     *
     * @param task 待执行任务，不能为 {@code null}
     * @param <T> 任务返回值类型
     * @return 可用于等待完成、获取结果、取消任务或观察异常的 {@code Future}
     * @throws IllegalArgumentException 任务为 {@code null} 时抛出
     * @throws RejectedExecutionException 工作线程和等待队列均已满时抛出
     */
    public static <T> Future<T> submit(Callable<T> task) {
        return EXECUTOR.submit(requireTask(task));
    }

    private static <T> T requireTask(T task) {
        if (task == null) {
            throw new IllegalArgumentException("任务不能为 null");
        }
        return task;
    }

    private static void rejectTask(Runnable task, ThreadPoolExecutor executor) {
        throw new RejectedExecutionException(
            "线程池任务已满，任务被拒绝：活动线程数=" + executor.getActiveCount()
                + "，当前排队任务数=" + executor.getQueue().size()
                + "，最大队列容量=" + DEFAULT_QUEUE_CAPACITY);
    }
}
