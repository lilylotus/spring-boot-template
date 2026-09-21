package cn.nihility.gw.trace;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

/**
 * Reactor Context 与 MDC 之间的桥接器。
 *
 * <p>注册之后，配合 {@code Hooks.enableAutomaticContextPropagation()}，Reactor 会在执行算子前
 * 把 Context 中 key 为 traceId 的值写入当前线程的 MDC，执行完再还原。
 * 这样业务代码里普通的 log.info 就能自动带上 traceId，不需要每处都手动塞。</p>
 */
public class TraceIdThreadLocalAccessor implements ThreadLocalAccessor<String> {

    /** Reactor Context 中的 key，与 MDC 的 key 保持一致。 */
    @Override
    public Object key() {
        return TraceContext.TRACE_ID;
    }

    /** 读取当前线程 MDC 中的 traceId，供 Reactor 抓取快照。 */
    @Override
    public String getValue() {
        return MDC.get(TraceContext.TRACE_ID);
    }

    /** 把 Context 中的 traceId 写入当前线程的 MDC。 */
    @Override
    public void setValue(String value) {
        MDC.put(TraceContext.TRACE_ID, value);
    }

    /** 算子执行结束后清理，避免 traceId 残留在被复用的事件循环线程上。 */
    @Override
    public void setValue() {
        MDC.remove(TraceContext.TRACE_ID);
    }

}
