package cn.nihility.gw.trace;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * 链路追踪上下文。
 *
 * <p>traceId 存放在 SLF4J 的 MDC(底层是 Log4j2 的 ThreadContext)中，日志模板通过
 * {@code %X{traceId}} 取用。MDC 本身是线程绑定的，所以这里额外提供快照(capture)/
 * 还原(restore)以及任务包装(wrap)能力，用来把上下文带到其它线程。</p>
 */
public final class TraceContext {

    /** MDC 中存放 traceId 的 key，必须与 log4j2-spring.xml 里的 %X{traceId} 保持一致。 */
    public static final String TRACE_ID = "traceId";

    /** 上下游透传 traceId 使用的 HTTP 头名称。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 工具类，禁止实例化。 */
    private TraceContext() {
    }

    /** 生成 32 位无连字符的 traceId，与 OpenTelemetry 的 trace id 长度保持一致，便于后续对接。 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 读取当前线程的 traceId，不存在时返回 null。 */
    public static String getTraceId() {
        return MDC.get(TRACE_ID);
    }

    /** 把 traceId 绑定到当前线程；传入空值时按新建处理，避免日志里出现空的 traceId。 */
    public static String setTraceId(String traceId) {
        String actual = (traceId == null || traceId.isBlank()) ? newTraceId() : traceId.trim();
        MDC.put(TRACE_ID, actual);
        return actual;
    }

    /** 清空当前线程的全部 MDC 内容。Web 容器和线程池都会复用线程，用完必须清理。 */
    public static void clear() {
        MDC.clear();
    }

    /** 抓取当前线程的 MDC 快照，用于跨线程传递；当前无上下文时返回 null。 */
    public static Map<String, String> capture() {
        return MDC.getCopyOfContextMap();
    }

    /** 用快照覆盖当前线程的 MDC；快照为空时等价于清空。 */
    public static void restore(Map<String, String> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
    }

    /**
     * 在指定 traceId 下执行一段逻辑，执行完还原线程原有的 traceId。
     *
     * <p>用于那些拿得到 traceId、但不确定当前线程 MDC 是否已就绪的场景，
     * 典型的是 Reactor 的 doFinally 回调。</p>
     */
    public static void runWith(String traceId, Runnable action) {
        String previous = MDC.get(TRACE_ID);
        MDC.put(TRACE_ID, traceId);
        try {
            action.run();
        } finally {
            if (previous == null) {
                MDC.remove(TRACE_ID);
            } else {
                MDC.put(TRACE_ID, previous);
            }
        }
    }

    /**
     * 把 Runnable 包装成携带「提交时」上下文的任务，供线程池使用。
     *
     * <p>快照在提交线程上抓取，在工作线程上还原；执行结束后必须还原工作线程原有的上下文，
     * 否则池化线程会把上一个任务的 traceId 带给下一个任务，造成串场。</p>
     */
    public static Runnable wrap(Runnable task) {
        Map<String, String> snapshot = capture();
        return () -> {
            Map<String, String> backup = capture();
            restore(snapshot);
            try {
                task.run();
            } finally {
                restore(backup);
            }
        };
    }

    /** 同 {@link #wrap(Runnable)}，用于有返回值的任务。 */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Map<String, String> snapshot = capture();
        return () -> {
            Map<String, String> backup = capture();
            restore(snapshot);
            try {
                return task.call();
            } finally {
                restore(backup);
            }
        };
    }

}
