package com.example.template.util;

import com.example.template.log4j2.MdcExecutorWrapper;
import org.apache.logging.log4j.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一封装的固定规格业务线程池工具类。核心/最大线程数均为 4，配合容量 2048 的有界队列，
 * 避免任务提交速率长期超过处理能力时无界堆积把内存打爆；线程带可识别的名称，便于日志/线程
 * dump 排查；队列打满时先记录拒绝原因再抛出异常，不静默丢任务；提交的任务会自动携带提交时刻
 * 的 traceId，方便异步任务的日志与发起请求的日志关联。
 * <p>
 * 静态方法为主，风格对齐 {@link HttpClientUtils}：内部持有一个进程级单例
 * {@link ThreadPoolExecutor}，不依赖 Spring 容器即可工作，线程数/队列容量当前是固定值，
 * 没有配置化的需求。
 */
public final class ThreadPoolUtils {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolUtils.class);

    /** 核心线程数，与最大线程数保持一致，避免弹性扩容带来的行为不确定性。 */
    private static final int CORE_POOL_SIZE = 4;

    /** 最大线程数。 */
    private static final int MAXIMUM_POOL_SIZE = 4;

    /** 有界队列容量，防止任务持续堆积把内存打爆。 */
    private static final int QUEUE_CAPACITY = 2048;

    /** 线程名前缀，实际线程名为该前缀加自增序号，如 async-pool-1。 */
    private static final String THREAD_NAME_PREFIX = "async-pool-";

    /** 线程默认空闲存活时间：超过这个时长没有任务可执行的线程会被回收。 */
    private static final long KEEP_ALIVE_SECONDS = 0L;

    /** JVM 关闭时等待线程池优雅终止的最长时间，超过后强制中断仍在执行的任务。 */
    private static final long SHUTDOWN_AWAIT_SECONDS = 30L;

    /** 进程级单例线程池，核心/最大线程数一致、有界队列、命名线程、带日志的拒绝策略。 */
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
            KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            new NamedThreadFactory(),
            new LoggingRejectedExecutionHandler());

    static {
        // JDK 默认只对"超过 corePoolSize 的额外线程"应用 keepAliveTime，本类
        // corePoolSize == maximumPoolSize，从来不会有额外线程，必须显式开启
        // allowCoreThreadTimeOut，KEEP_ALIVE_SECONDS 才会真正对这 4 个核心线程生效。
        // EXECUTOR.allowCoreThreadTimeOut(true);

        // 没有 Spring 容器的 @PreDestroy 可用，用 JVM 关闭钩子做优雅关闭：
        // 停止接收新任务、等待已提交任务在超时时间内跑完，而不是被直接杀死。
        Runtime.getRuntime().addShutdownHook(new Thread(ThreadPoolUtils::shutdownGracefully, "async-pool-shutdown-hook"));
    }

    /**
     * 工具类不允许实例化。
     */
    private ThreadPoolUtils() {
    }

    /**
     * 提交一个无返回值的异步任务。任务执行时能通过 {@code ThreadContext} 访问到提交线程当时的
     * traceId；线程池核心线程与队列都已占满时会先记录拒绝原因再抛出
     * {@link java.util.concurrent.RejectedExecutionException}，调用方需要自行处理。
     *
     * @param task 待异步执行的任务
     */
    public static void execute(Runnable task) {
        EXECUTOR.execute(MdcExecutorWrapper.wrap(task));
    }

    /**
     * 提交一个有返回值的异步任务，语义与 {@link #execute(Runnable)} 一致（traceId 传递、
     * 拒绝时先记录日志再抛异常），额外返回一个 {@link Future} 供调用方获取结果。
     *
     * @param task 待异步执行的任务
     * @param <T>  任务返回值类型
     * @return 该任务的 {@link Future}
     */
    public static <T> Future<T> submit(Callable<T> task) {
        return EXECUTOR.submit(wrapCallable(task));
    }

    /**
     * 给 {@link Callable} 包一层 MDC 上下文传递逻辑，效果等价于
     * {@link MdcExecutorWrapper#wrap(Runnable)}，只是签名换成了有返回值的 {@link Callable}。
     *
     * @param task 原始任务
     * @param <T>  任务返回值类型
     * @return 包装后的任务：执行前还原提交线程当时的 MDC 上下文，执行完毕后清理
     */
    private static <T> Callable<T> wrapCallable(Callable<T> task) {
        // 在提交任务的这一刻，捕获当前线程的 MDC 上下文快照，与 MdcExecutorWrapper 的做法一致。
        Map<String, String> contextMap = ThreadContext.getImmutableContext();
        return () -> {
            try {
                if (contextMap != null) {
                    ThreadContext.putAll(contextMap);
                }
                return task.call();
            } finally {
                // 执行完清理，避免线程池复用线程时 MDC 污染下一个任务。
                ThreadContext.clearAll();
            }
        };
    }

    /**
     * 优雅关闭线程池：停止接收新任务，等待已提交任务在超时时间内执行完毕；超时仍未完成，
     * 或者等待过程被中断，则强制中断线程池中的任务。
     */
    private static void shutdownGracefully() {
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            // 恢复中断标志位，交还给调用方（这里是 JVM 关闭线程）自行决定如何处理。
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 给线程池创建的工作线程统一命名（前缀 + 自增序号），替代 JDK 默认的
     * {@code pool-N-thread-M}，便于日志和线程 dump 排查是哪个业务线程池的线程。
     */
    private static final class NamedThreadFactory implements ThreadFactory {

        /** 线程序号生成器，从 1 开始自增，保证并发创建线程时序号不重复。 */
        private final AtomicInteger sequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + sequence.getAndIncrement());
            // 显式设置为非守护线程，避免 JVM 在任务还没跑完时因为"只剩守护线程"而提前退出。
            thread.setDaemon(false);
            return thread;
        }

    }

    /**
     * 队列打满、线程池无法接纳新任务时的拒绝策略：先以 WARN 级别记录线程池当前状态和被拒绝
     * 任务的信息，方便排查是任务本身太慢还是提交速率异常；日志记录完之后委托给
     * {@link ThreadPoolExecutor.AbortPolicy}，仍然向调用方抛出
     * {@link java.util.concurrent.RejectedExecutionException}，不静默丢弃任务。
     */
    private static final class LoggingRejectedExecutionHandler implements RejectedExecutionHandler {

        /** 拒绝任务时仍然抛异常给调用方，只是在抛之前多做一次日志记录。 */
        private final RejectedExecutionHandler delegate = new ThreadPoolExecutor.AbortPolicy();

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            log.warn(
                    "线程池 [{}] 拒绝了任务 [{}]：活跃线程数=[{}]，队列积压任务数=[{}]，已完成任务数=[{}]",
                    THREAD_NAME_PREFIX, task, executor.getActiveCount(),
                    executor.getQueue().size(), executor.getCompletedTaskCount());
            delegate.rejectedExecution(task, executor);
        }

    }

}
