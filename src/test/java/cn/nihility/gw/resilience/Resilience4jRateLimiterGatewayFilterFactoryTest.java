package cn.nihility.gw.resilience;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Resilience4jRateLimiterGatewayFilterFactory} 的单元测试。
 *
 * <p>不起 Spring 上下文，直接拿 resilience4j 的注册表构造过滤器，用 MockServerWebExchange 走一遍。</p>
 */
class Resilience4jRateLimiterGatewayFilterFactoryTest {

    private static final String INSTANCE = "testRateLimiter";

    /** 配额 1 次/周期、拿不到许可立即返回，是网关里唯一安全的配置形态。 */
    private static RateLimiterRegistry registryWith(int limitForPeriod, Duration timeout) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(timeout)
                .build();
        return RateLimiterRegistry.of(config);
    }

    private static Resilience4jRateLimiterGatewayFilterFactory factoryWith(RateLimiterRegistry registry) {
        return new Resilience4jRateLimiterGatewayFilterFactory(
                registry, new DegradeResponseWriter(new ObjectMapper()));
    }

    private static Resilience4jRateLimiterGatewayFilterFactory.Config config() {
        Resilience4jRateLimiterGatewayFilterFactory.Config config =
                new Resilience4jRateLimiterGatewayFilterFactory.Config();
        config.setName(INSTANCE);
        return config;
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/proxy/anything").build());
    }

    /** 配额内放行，超配额返回 429 且不再往下走过滤器链。 */
    @Test
    void shouldPassWithinQuotaAndRejectBeyond() {
        GatewayFilter filter = factoryWith(registryWith(1, Duration.ZERO)).apply(config());

        MockServerWebExchange first = exchange();
        filter.filter(first, ex -> Mono.empty()).block();
        assertNull(first.getResponse().getStatusCode(), "放行时不应改写状态码");

        MockServerWebExchange second = exchange();
        filter.filter(second, ex -> {
            throw new AssertionError("超配额的请求不应继续走过滤器链");
        }).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());
    }

    /** 拒绝时写出的是约定的降级 JSON。 */
    @Test
    void shouldWriteDegradeJsonOnReject() {
        GatewayFilter filter = factoryWith(registryWith(1, Duration.ZERO)).apply(config());
        filter.filter(exchange(), ex -> Mono.empty()).block();

        MockServerWebExchange rejected = exchange();
        filter.filter(rejected, ex -> Mono.empty()).block();

        String body = rejected.getResponse().getBodyAsString().block();
        assertTrue(body != null && body.contains(DegradeResponseWriter.REASON_RATE_LIMITED),
                "实际响应体: " + body);
        assertEquals("application/json",
                rejected.getResponse().getHeaders().getContentType().toString());
    }

    /**
     * timeout-duration 非零时装配期就要失败。
     *
     * <p>非零值会让 acquirePermission 阻塞 Netty 事件循环，等到线上流量打满才暴露就太晚了。</p>
     */
    @Test
    void shouldRejectBlockingTimeoutAtAssembly() {
        Resilience4jRateLimiterGatewayFilterFactory factory =
                factoryWith(registryWith(1, Duration.ofSeconds(5)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> factory.apply(config()));

        assertTrue(error.getMessage().contains("timeout-duration"), "实际信息: " + error.getMessage());
        assertTrue(error.getMessage().contains("阻塞"), "错误信息要说清楚后果，实际: " + error.getMessage());
    }

    /**
     * 拒绝路径不阻塞。
     *
     * <p>刷新周期是 10 秒，若 acquirePermission 走的是等待配额的分支，这里会卡住十秒；
     * 断言耗时远小于刷新周期即可证明它是立即返回的。</p>
     */
    @Test
    void shouldRejectWithoutBlocking() {
        GatewayFilter filter = factoryWith(registryWith(1, Duration.ZERO)).apply(config());
        filter.filter(exchange(), ex -> Mono.empty()).block();

        long startNanos = System.nanoTime();
        filter.filter(exchange(), ex -> Mono.empty()).block();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(elapsedMillis < 500L,
                "拒绝路径不能等待配额释放，实际耗时 " + elapsedMillis + "ms（刷新周期是 10s）");
    }

}
