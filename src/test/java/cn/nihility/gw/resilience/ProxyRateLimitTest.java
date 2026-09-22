package cn.nihility.gw.resilience;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import cn.nihility.gw.trace.TraceContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * /proxy 路由的限流行为集成测试。
 *
 * <p>用一个真实的打桩下游顶替 BootDemo（通过 SimpleDiscoveryClient 注册进服务发现），
 * 这样才能断言「被限流的请求确实没有打到下游」——这件事只有下游自己数得准。</p>
 *
 * <p>配额压到 2 次/秒，熔断的 minimum-number-of-calls 抬到 100 让它在本类中不会介入。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "resilience4j.ratelimiter.instances.bootDemoRateLimiter.limit-for-period=2",
                "resilience4j.ratelimiter.instances.bootDemoRateLimiter.limit-refresh-period=1s",
                "resilience4j.ratelimiter.instances.bootDemoRateLimiter.timeout-duration=0",
                "resilience4j.circuitbreaker.instances.bootDemoCircuitBreaker.minimum-number-of-calls=100"
        })
class ProxyRateLimitTest {

    /** 每个刷新周期的配额，与上面的 limit-for-period 保持一致。 */
    private static final int QUOTA = 2;

    /** 限流刷新周期。 */
    private static final Duration REFRESH_PERIOD = Duration.ofSeconds(1);

    /** 打桩下游必须在静态初始化阶段就绪：@DynamicPropertySource 在上下文刷新时求值，早于 @BeforeAll。 */
    private static final StubDownstreamServer STUB = new StubDownstreamServer();

    @DynamicPropertySource
    static void registerStubAsBootDemo(DynamicPropertyRegistry registry) {
        // 关掉 nacos 后 SimpleDiscoveryClient 是唯一的服务发现实现，lb://BootDemo 会解析到这里
        registry.add("spring.cloud.discovery.client.simple.instances[BootDemo][0].uri", STUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;

    /**
     * 每个用例都从一个干净的限流周期开始。
     *
     * <p>RateLimiter 的配额是进程级共享状态，上一个用例用掉的配额会漏到下一个用例，
     * 所以这里等满一个刷新周期再开始。</p>
     */
    @BeforeEach
    void waitForFreshPeriod() throws InterruptedException {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
        Thread.sleep(REFRESH_PERIOD.toMillis() + 200L);
        STUB.resetRequestCount();
        STUB.respondWith(200);
    }

    /** 配额内放行、超配额立刻 429 且不穿透下游、下一周期恢复。 */
    @Test
    void shouldRejectBeyondQuotaAndRecoverNextPeriod() throws InterruptedException {
        for (int i = 0; i < QUOTA; i++) {
            client.get().uri("/proxy/anything").exchange().expectStatus().isOk();
        }
        assertEquals(QUOTA, STUB.requestCount(), "配额内的请求都应转发到下游");

        // 第 QUOTA+1 个请求超出配额
        client.get().uri("/proxy/anything").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody()
                .jsonPath("$.reason").isEqualTo(DegradeResponseWriter.REASON_RATE_LIMITED);
        assertEquals(QUOTA, STUB.requestCount(), "被限流的请求不能穿透到下游");

        // 等下一个刷新周期，配额恢复
        Thread.sleep(REFRESH_PERIOD.toMillis() + 200L);
        client.get().uri("/proxy/anything").exchange().expectStatus().isOk();
        assertEquals(QUOTA + 1, STUB.requestCount(), "新周期应恢复转发");
    }

    /** /proxy 被限流时，网关自身的接口不受影响。 */
    @Test
    void shouldNotAffectOtherPaths() {
        for (int i = 0; i < QUOTA; i++) {
            client.get().uri("/proxy/anything").exchange().expectStatus().isOk();
        }
        client.get().uri("/proxy/anything").exchange().expectStatus().isEqualTo(429);

        // /welcome 是网关自己的接口，不走代理路由，也就不共享这份配额
        client.get().uri("/welcome").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.message").isEqualTo("WelcomeSuccess");
    }

    /** 限流的降级响应带上本次请求的 traceId，且不泄漏内部细节。 */
    @Test
    void shouldCarryTraceIdAndHideInternals() {
        String traceId = "ratelimittraceid0123456789abcdef";

        for (int i = 0; i < QUOTA; i++) {
            client.get().uri("/proxy/anything").exchange().expectStatus().isOk();
        }

        byte[] body = client.get().uri("/proxy/anything")
                .header(TraceContext.TRACE_ID_HEADER, traceId)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(TraceContext.TRACE_ID_HEADER, traceId)
                .expectBody()
                .jsonPath("$.traceId").isEqualTo(traceId)
                .jsonPath("$.message").isNotEmpty()
                .returnResult()
                .getResponseBody();

        DegradeResponseAssertions.assertNoInternalDetails(body);
    }

}
