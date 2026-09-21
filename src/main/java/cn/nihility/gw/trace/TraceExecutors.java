package cn.nihility.gw.trace;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 线程池包装工具：把已有的线程池包一层，使提交进去的任务自动继承提交方的 traceId。
 *
 * <p>适用于手写的 ExecutorService。Spring 托管的线程池用 {@link TraceTaskDecorator} 即可。</p>
 */
public final class TraceExecutors {

    /** 工具类，禁止实例化。 */
    private TraceExecutors() {
    }

    /** 包装 Executor，execute 提交的任务会带上提交线程的 traceId。 */
    public static Executor wrap(Executor delegate) {
        return command -> delegate.execute(TraceContext.wrap(command));
    }

    /** 包装 ExecutorService，submit/execute/invokeAll 提交的任务都会带上提交线程的 traceId。 */
    public static ExecutorService wrap(ExecutorService delegate) {
        return new TraceExecutorService(delegate);
    }

    /**
     * 透传 traceId 的 ExecutorService 装饰器。
     *
     * <p>继承 AbstractExecutorService 是因为它的 submit/invokeAll/invokeAny 最终都会走到
     * {@link #execute(Runnable)}，所以只需在这一个入口做包装，其余方法直接委托即可。</p>
     */
    private static final class TraceExecutorService extends AbstractExecutorService {

        /** 真正干活的线程池。 */
        private final ExecutorService delegate;

        private TraceExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(TraceContext.wrap(command));
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

    }

}
