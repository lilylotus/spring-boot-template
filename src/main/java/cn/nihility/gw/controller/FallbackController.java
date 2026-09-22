package cn.nihility.gw.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import cn.nihility.gw.resilience.DegradeResponseWriter;

/**
 * 熔断降级接口。
 *
 * <p>网关内置的 CircuitBreaker 过滤器在熔断打开时会 {@code forward:} 到这里，响应体结构与限流时
 * 由 {@link DegradeResponseWriter} 写出的完全一致，调用方只需按状态码区分：熔断 503、限流 429。</p>
 *
 * <p>路径特意放在 {@code /fallback/**} 而不是 {@code /proxy/**} 下，否则会被代理路由再匹配一次，
 * 形成转发回环。</p>
 */
@RestController
public class FallbackController {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackController.class);

    /** 熔断打开时返回给调用方的提示。 */
    private static final String FALLBACK_MESSAGE = "下游服务暂时不可用，请稍后重试";

    private final DegradeResponseWriter degradeResponseWriter;

    public FallbackController(DegradeResponseWriter degradeResponseWriter) {
        this.degradeResponseWriter = degradeResponseWriter;
    }

    /**
     * BootDemo 的熔断降级响应。
     *
     * <p>不限定 HTTP 方法：{@code forward:} 会沿用原始请求的方法，被熔断的 POST 请求转到这里时
     * 仍然是 POST。</p>
     *
     * @param exchange 当前请求的 exchange，用于取 traceId
     * @return 503 对应的降级响应体，含拦截原因、提示信息与 traceId
     */
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @RequestMapping("/fallback/boot-demo")
    public Mono<Map<String, Object>> bootDemoFallback(ServerWebExchange exchange) {
        // 用 fromSupplier 把副作用推迟到订阅时执行，与 WelcomeController 的写法保持一致
        return Mono.fromSupplier(() -> {
            LOG.warn("BootDemo 熔断降级，路径 [{}]", exchange.getRequest().getURI().getPath());
            return degradeResponseWriter.body(
                    DegradeResponseWriter.REASON_CIRCUIT_BREAKER_OPEN,
                    FALLBACK_MESSAGE,
                    degradeResponseWriter.resolveTraceId(exchange));
        });
    }

}
