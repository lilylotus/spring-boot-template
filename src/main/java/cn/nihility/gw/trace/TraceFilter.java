package cn.nihility.gw.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * 链路追踪与接口耗时过滤器(WebFlux 版)。
 *
 * <p>职责有三个：为每个请求准备 traceId(优先复用上游传来的)、把 traceId 透传给下游服务和调用方、
 * 在请求结束后输出一行耗时日志，格式为 {@code GET /welcome cost [10]ms}。</p>
 *
 * <p>WebFlux 下一个请求会在多个线程间流转，MDC 这种 ThreadLocal 存不住，所以 traceId 真正的载体是
 * Reactor Context；再由 {@link TraceIdThreadLocalAccessor} 配合 Reactor 的自动上下文传播，
 * 在算子执行所在的线程上把它还原进 MDC，日志模板的 %X{traceId} 才能取到值。</p>
 */
public class TraceFilter implements WebFilter, Ordered {

    /** 输出 traceId 与接口耗时的日志器。 */
    private static final Logger LOG = LoggerFactory.getLogger(TraceFilter.class);

    /** 纳秒转毫秒的除数，用 nanoTime 计时是因为它单调递增、不受系统时钟调整影响。 */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = resolveTraceId(exchange.getRequest());

        // 写进请求头：网关转发时(NettyRoutingFilter)是从 exchange 的请求头取值发往下游的，
        // 不放进去 traceId 就会止步于网关，传不到下游服务
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(TraceContext.TRACE_ID_HEADER, traceId)
                .build();
        ServerWebExchange traced = exchange.mutate().request(request).build();
        // 回写响应头，方便调用方/前端拿着 traceId 来排查问题
        traced.getResponse().getHeaders().set(TraceContext.TRACE_ID_HEADER, traceId);

        String method = request.getMethod().name();
        String path = request.getURI().getPath();
        long startNanos = System.nanoTime();

        return chain.filter(traced)
                // doFinally 覆盖正常结束/异常/取消三种信号，保证耗时日志一定会打印
                .doFinally(signal -> logCost(method, path, traceId, startNanos))
                // 放进 Reactor Context，向上游算子(即后续的过滤器与业务处理)传递
                .contextWrite(Context.of(TraceContext.TRACE_ID, traceId));
    }

    /** 优先沿用上游传来的 traceId，保证一次调用链在所有服务里是同一个 id。 */
    private String resolveTraceId(ServerHttpRequest request) {
        String upstream = request.getHeaders().getFirst(TraceContext.TRACE_ID_HEADER);
        if (upstream == null || upstream.isBlank()) {
            return TraceContext.newTraceId();
        }
        return upstream.trim();
    }

    /** 打印接口耗时。 */
    private void logCost(String method, String path, String traceId, long startNanos) {
        long costMillis = (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
        // doFinally 的回调不保证自动上下文传播已经把 MDC 还原好(取消信号时尤其如此)，
        // 这里显式绑定 traceId 再打印，打完还原，避免污染这条线程
        TraceContext.runWith(traceId, () -> LOG.info("{} {} cost [{}]ms", method, path, costMillis));
    }

    /** 排在最前面，保证 traceId 在其它过滤器和网关转发逻辑之前就绪。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

}
