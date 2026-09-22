package cn.nihility.gw.resilience;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code resilience4j.*.configs.default} 同时覆盖两种「没有配置」。
 *
 * <p>一是 {@code instances} 下列了名字但没填参数的实例；二是压根没列出来、由代码按名字临时创建的实例。
 * 后者尤其重要：新增一条路由时忘了加配置，也必须是有保护的，而不是退化成 resilience4j 的库默认值——
 * 库默认的限流等待 5 秒会阻塞 Netty 事件循环，库默认的超时 1 秒会把所有请求截断。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
class ResilienceDefaultConfigTest {

    /** 配置里不存在的实例名，用来触发「按名字临时创建」这条路径。 */
    private static final String UNCONFIGURED = "noSuchInstanceInConfiguration";

    /** resilience4j 库自带的限流默认等待时长，本项目绝不能落到这个值上。 */
    private static final Duration LIBRARY_DEFAULT_RATELIMITER_TIMEOUT = Duration.ofSeconds(5);

    /** resilience4j 库自带的超时默认值，本项目绝不能落到这个值上。 */
    private static final Duration LIBRARY_DEFAULT_TIMELIMITER_TIMEOUT = Duration.ofSeconds(1);

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private TimeLimiterRegistry timeLimiterRegistry;

    /** 列了名字但没填参数的实例，取值应与 configs.default 一致。 */
    @Test
    void listedInstanceWithoutParamsShouldInheritDefault() {
        RateLimiterConfig actual = rateLimiterRegistry.rateLimiter("bootDemoRateLimiter").getRateLimiterConfig();
        RateLimiterConfig expected = rateLimiterRegistry.getDefaultConfig();

        assertEquals(expected.getLimitForPeriod(), actual.getLimitForPeriod());
        assertEquals(expected.getLimitRefreshPeriod(), actual.getLimitRefreshPeriod());
        assertEquals(expected.getTimeoutDuration(), actual.getTimeoutDuration());

        CircuitBreakerConfig circuitBreaker = circuitBreakerRegistry
                .circuitBreaker("bootDemoCircuitBreaker").getCircuitBreakerConfig();
        CircuitBreakerConfig circuitBreakerDefault = circuitBreakerRegistry.getDefaultConfig();
        assertEquals(circuitBreakerDefault.getFailureRateThreshold(), circuitBreaker.getFailureRateThreshold());
        assertEquals(circuitBreakerDefault.getMinimumNumberOfCalls(), circuitBreaker.getMinimumNumberOfCalls());
        assertEquals(circuitBreakerDefault.getSlowCallDurationThreshold(),
                circuitBreaker.getSlowCallDurationThreshold());
    }

    /** 压根没列出来的实例，拿到的也应该是项目默认值，而不是库默认值。 */
    @Test
    void unlistedInstanceShouldAlsoInheritDefault() {
        RateLimiterConfig rateLimiter = rateLimiterRegistry.rateLimiter(UNCONFIGURED).getRateLimiterConfig();

        assertEquals(Duration.ZERO, rateLimiter.getTimeoutDuration(),
                "未配置的限流实例必须是非阻塞的，落到库默认的 5s 会阻塞 Netty 事件循环");
        assertNotEquals(LIBRARY_DEFAULT_RATELIMITER_TIMEOUT, rateLimiter.getTimeoutDuration());
        assertEquals(rateLimiterRegistry.getDefaultConfig().getLimitForPeriod(), rateLimiter.getLimitForPeriod());
    }

    /** 未配置的超时实例不能落到库默认的 1 秒，否则请求会被静默截断。 */
    @Test
    void unlistedTimeLimiterShouldNotFallBackToOneSecond() {
        Duration actual = timeLimiterRegistry.timeLimiter(UNCONFIGURED)
                .getTimeLimiterConfig().getTimeoutDuration();

        assertNotEquals(LIBRARY_DEFAULT_TIMELIMITER_TIMEOUT, actual,
                "未配置的超时实例落到 1 秒，会把所有请求静默截断");
        assertTrue(actual.compareTo(LIBRARY_DEFAULT_TIMELIMITER_TIMEOUT) > 0,
                "项目默认超时应明显大于库默认的 1 秒，实际: " + actual);
        assertEquals(timeLimiterRegistry.getDefaultConfig().getTimeoutDuration(), actual);
    }

    /** 未配置的实例也能被限流过滤器正常装配——装配期的非阻塞校验不该把它拦下。 */
    @Test
    void unlistedInstanceShouldPassFilterAssemblyCheck() {
        Resilience4jRateLimiterGatewayFilterFactory factory = new Resilience4jRateLimiterGatewayFilterFactory(
                rateLimiterRegistry, new DegradeResponseWriter(new com.fasterxml.jackson.databind.ObjectMapper()));

        Resilience4jRateLimiterGatewayFilterFactory.Config config =
                new Resilience4jRateLimiterGatewayFilterFactory.Config();
        config.setName(UNCONFIGURED);

        // 不抛异常即为通过：加了 configs.default 之后，漏配不再是启动失败，而是套用默认配额
        factory.apply(config);
    }

}
