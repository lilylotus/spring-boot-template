package cn.nihility.gw.resilience;

import java.time.Duration;
import java.util.List;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 基于 resilience4j 的网关限流过滤器工厂。
 *
 * <p>为什么要自己写：网关自带的限流器只有 {@code RedisRateLimiter} 和 {@code Bucket4jRateLimiter}，
 * 没有 resilience4j 版本，{@code RequestRateLimiter=} 过滤器绑定的是网关自己的 RateLimiter 接口，
 * 接不上 resilience4j 的注册表。</p>
 *
 * <p>配置用法(简写形式，参数是 resilience4j 的 RateLimiter 实例名)：</p>
 *
 * <pre>
 * filters:
 *   - Resilience4jRateLimiter=bootDemoRateLimiter
 * </pre>
 *
 * <p>配额由整条路由的所有调用方共享，不按调用方身份区分；超配额的请求立刻拿到 429，不排队。</p>
 *
 * <p><b>注意这是单实例内存级限流。</b>状态只存在于当前 JVM，网关部署 N 个实例时集群实际放行量是
 * 配置值的 N 倍。需要精确的全局配额只能换成 Redis + 网关内置的 RequestRateLimiter。</p>
 */
@Component
public class Resilience4jRateLimiterGatewayFilterFactory
        extends AbstractGatewayFilterFactory<Resilience4jRateLimiterGatewayFilterFactory.Config> {

    private static final Logger LOG = LoggerFactory.getLogger(Resilience4jRateLimiterGatewayFilterFactory.class);

    /** 超配额时返回给调用方的提示。 */
    private static final String REJECT_MESSAGE = "请求过于频繁，请稍后重试";

    private final RateLimiterRegistry rateLimiterRegistry;

    private final DegradeResponseWriter degradeResponseWriter;

    public Resilience4jRateLimiterGatewayFilterFactory(RateLimiterRegistry rateLimiterRegistry,
                                                       DegradeResponseWriter degradeResponseWriter) {
        super(Config.class);
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.degradeResponseWriter = degradeResponseWriter;
    }

    /** 支持 {@code Resilience4jRateLimiter=实例名} 的简写形式。 */
    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("name");
    }

    @Override
    public GatewayFilter apply(Config config) {
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(config.getName());
        requireNonBlocking(rateLimiter);

        LOG.info("Resilience4jRateLimiter [{}] 已装配，配额 [{}] 次 / [{}]",
                rateLimiter.getName(),
                rateLimiter.getRateLimiterConfig().getLimitForPeriod(),
                rateLimiter.getRateLimiterConfig().getLimitRefreshPeriod());

        return (exchange, chain) -> {
            // timeoutDuration 为 0 时这里不会阻塞：拿不到许可立即返回 false
            if (rateLimiter.acquirePermission()) {
                return chain.filter(exchange);
            }
            LOG.warn("请求被限流，实例 [{}]，路径 [{}]", rateLimiter.getName(), exchange.getRequest().getURI().getPath());
            return degradeResponseWriter.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                    DegradeResponseWriter.REASON_RATE_LIMITED, REJECT_MESSAGE);
        };
    }

    /**
     * 校验该 RateLimiter 不会在拿不到许可时阻塞调用线程。
     *
     * <p>resilience4j 的 {@code acquirePermission()} 在配额耗尽时会阻塞当前线程直到 timeoutDuration
     * 超时。在 WebFlux 里这个线程是 Netty 事件循环，一旦阻塞就是全局性事故——限流反而成了压垮网关的
     * 原因。所以这里在装配期就拦住配置错误，而不是等到线上流量打满才暴露。</p>
     *
     * <p>顺带也能兜住「实例名写错、配置里根本没有这个实例」的情况：此时 resilience4j 会用默认配置，
     * 而默认的 timeoutDuration 是 5 秒，同样过不了这道校验。</p>
     *
     * @param rateLimiter 待校验的限流器
     * @throws IllegalStateException 当 timeoutDuration 不为零时抛出
     */
    private void requireNonBlocking(RateLimiter rateLimiter) {
        Duration timeoutDuration = rateLimiter.getRateLimiterConfig().getTimeoutDuration();
        if (!timeoutDuration.isZero()) {
            throw new IllegalStateException(String.format(
                    "RateLimiter [%s] 的 timeout-duration 是 [%s]，必须配成 0。"
                            + "非零值会让 acquirePermission 阻塞 Netty 事件循环线程，"
                            + "限流本身就会压垮网关。请检查 resilience4j.ratelimiter.instances.%s.timeout-duration"
                            + "(实例名写错时会落到默认配置，默认就是 5 秒)。",
                    rateLimiter.getName(), timeoutDuration, rateLimiter.getName()));
        }
    }

    /** 过滤器配置，只有一个字段：resilience4j 中 RateLimiter 实例的名字。 */
    public static class Config {

        /** resilience4j.ratelimiter.instances 下的实例名。 */
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

    }

}
