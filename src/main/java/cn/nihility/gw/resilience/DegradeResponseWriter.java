package cn.nihility.gw.resilience;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import cn.nihility.gw.trace.TraceContext;

/**
 * 降级响应的统一构造与写出。
 *
 * <p>熔断和限流是两条不同的拦截路径(前者由网关内置的 CircuitBreaker 过滤器 forward 到降级接口，
 * 后者由 {@link Resilience4jRateLimiterGatewayFilterFactory} 直接写响应)，调用方却应该看到同一套
 * JSON 结构，所以把构造逻辑收在这里，两边共用。</p>
 *
 * <p>响应体只放拦截原因、给人读的提示和 traceId，不带异常堆栈、下游实例地址或框架内部类名。</p>
 */
@Component
public class DegradeResponseWriter {

    /** 熔断打开导致的拦截。 */
    public static final String REASON_CIRCUIT_BREAKER_OPEN = "CIRCUIT_BREAKER_OPEN";

    /** 超出请求配额导致的拦截。 */
    public static final String REASON_RATE_LIMITED = "RATE_LIMITED";

    private final ObjectMapper objectMapper;

    public DegradeResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 构造降级响应体。
     *
     * @param reason 拦截原因标识，取本类的 {@code REASON_*} 常量
     * @param message 给人读的提示信息
     * @param traceId 本次请求的 traceId；为 null 或空白时以空字符串占位，保证字段恒存在
     * @return 有序的响应体，字段顺序为 reason、message、traceId
     */
    public Map<String, Object> body(String reason, String message, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>(3);
        body.put("reason", reason);
        body.put("message", message);
        body.put("traceId", (traceId == null || traceId.isBlank()) ? "" : traceId);
        return body;
    }

    /**
     * 把降级响应直接写入 exchange 并结束该请求。
     *
     * <p>供过滤器使用。调用前必须确保响应尚未提交——限流过滤器排在路由过滤器链的最前面，
     * 这个前提成立。</p>
     *
     * @param exchange 当前请求的 exchange
     * @param status 响应状态码
     * @param reason 拦截原因标识
     * @param message 给人读的提示信息
     * @return 写出完成的信号
     */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String reason, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body(reason, message, resolveTraceId(exchange)));
        } catch (JsonProcessingException e) {
            // 响应体只有三个 String 字段，序列化失败属于不该发生的情况；
            // 真发生了也不能吞掉，否则调用方会收到一个没有响应体的 429/503
            return Mono.error(e);
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 取本次请求的 traceId。
     *
     * <p>优先从请求头取：{@code TraceFilter} 排在 {@code HIGHEST_PRECEDENCE}，到这里时它已经把
     * traceId 写进了请求头和响应头，从 exchange 上取是确定的；而 MDC 依赖 Reactor 的上下文传播
     * 在当前线程还原，时机上不如前者可靠。两者都取不到才返回 null。</p>
     *
     * @param exchange 当前请求的 exchange
     * @return traceId，取不到时为 null
     */
    public String resolveTraceId(ServerWebExchange exchange) {
        String fromRequest = exchange.getRequest().getHeaders().getFirst(TraceContext.TRACE_ID_HEADER);
        if (fromRequest != null && !fromRequest.isBlank()) {
            return fromRequest;
        }
        String fromResponse = exchange.getResponse().getHeaders().getFirst(TraceContext.TRACE_ID_HEADER);
        if (fromResponse != null && !fromResponse.isBlank()) {
            return fromResponse;
        }
        return TraceContext.getTraceId();
    }

}
