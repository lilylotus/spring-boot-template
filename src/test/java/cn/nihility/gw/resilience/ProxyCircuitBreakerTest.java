package cn.nihility.gw.resilience;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
 * /proxy 路由的熔断行为集成测试。
 *
 * <p>窗口压到 4 次调用、冷却 1 秒，好让用例在秒级内跑完；限流配额抬高到 1000，
 * 避免它在本类中介入。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "resilience4j.ratelimiter.instances.bootDemoRateLimiter.limit-for-period=1000",
                "resilience4j.circuitbreaker.instances.bootDemoCircuitBreaker.sliding-window-size=4",
                "resilience4j.circuitbreaker.instances.bootDemoCircuitBreaker.minimum-number-of-calls=4",
                "resilience4j.circuitbreaker.instances.bootDemoCircuitBreaker.failure-rate-threshold=50",
                "resilience4j.circuitbreaker.instances.bootDemoCircuitBreaker.wait-duration-in-open-state=1s",
                "resilience4j.circuitbreaker.instances.bootDemoCircuitBreaker"
                        + ".permitted-number-of-calls-in-half-open-state=1"
        })
class ProxyCircuitBreakerTest {

    /** 打满一个统计窗口所需的调用次数，与 minimum-number-of-calls 一致。 */
    private static final int WINDOW_SIZE = 4;

    /** 熔断打开后的冷却时长，与 wait-duration-in-open-state 一致。 */
    private static final Duration OPEN_STATE_WAIT = Duration.ofSeconds(1);

    /** 打桩下游必须在静态初始化阶段就绪，见 ProxyRateLimitTest 的同名说明。 */
    private static final StubDownstreamServer STUB = new StubDownstreamServer();

    @DynamicPropertySource
    static void registerStubAsBootDemo(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances[BootDemo][0].uri", STUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private WebTestClient client;

    /** 熔断器是进程级共享状态，每个用例开始前重置，否则用例之间会互相干扰。 */
    @BeforeEach
    void resetState() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
        circuitBreakerRegistry.circuitBreaker("bootDemoCircuitBreaker").reset();
        STUB.resetRequestCount();
        STUB.respondWith(200);
        STUB.respondAfter(Duration.ZERO);
    }

    /** 下游持续 5xx 打满窗口后熔断打开，随后的请求直接降级且不再穿透下游。 */
    @Test
    void shouldOpenAfterDownstreamFailuresAndStopForwarding() {
        STUB.respondWith(500);
        for (int i = 0; i < WINDOW_SIZE; i++) {
            client.get().uri("/proxy/anything").exchange().expectStatus().is5xxServerError();
        }
        int countWhenOpened = STUB.requestCount();
        assertEquals(WINDOW_SIZE, countWhenOpened, "打开熔断前的调用都应真实打到下游");

        client.get().uri("/proxy/anything").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody()
                .jsonPath("$.reason").isEqualTo(DegradeResponseWriter.REASON_CIRCUIT_BREAKER_OPEN);

        assertEquals(countWhenOpened, STUB.requestCount(), "熔断打开后不能再穿透到下游");
        assertEquals(CircuitBreaker.State.OPEN,
                circuitBreakerRegistry.circuitBreaker("bootDemoCircuitBreaker").getState());
    }

    /** 冷却结束、下游恢复后，半开试探成功则熔断关闭、转发恢复。 */
    @Test
    void shouldRecoverAfterCoolDownWhenDownstreamHealthy() throws InterruptedException {
        STUB.respondWith(500);
        for (int i = 0; i < WINDOW_SIZE; i++) {
            client.get().uri("/proxy/anything").exchange().expectStatus().is5xxServerError();
        }
        client.get().uri("/proxy/anything").exchange().expectStatus().isEqualTo(503);

        STUB.respondWith(200);
        Thread.sleep(OPEN_STATE_WAIT.toMillis() + 500L);

        // 半开状态只放行 permitted-number-of-calls-in-half-open-state 个试探请求
        client.get().uri("/proxy/anything").exchange().expectStatus().isOk();

        assertEquals(CircuitBreaker.State.CLOSED,
                circuitBreakerRegistry.circuitBreaker("bootDemoCircuitBreaker").getState());
        client.get().uri("/proxy/anything").exchange().expectStatus().isOk();
    }

    /** 下游 4xx 是调用方的问题，不计入失败率，熔断保持关闭且状态码原样透传。 */
    @Test
    void shouldNotTripOnDownstream4xx() {
        STUB.respondWith(404);
        for (int i = 0; i < WINDOW_SIZE * 2; i++) {
            client.get().uri("/proxy/anything").exchange().expectStatus().isNotFound();
        }

        assertEquals(CircuitBreaker.State.CLOSED,
                circuitBreakerRegistry.circuitBreaker("bootDemoCircuitBreaker").getState(),
                "4xx 不应触发熔断");
        assertEquals(WINDOW_SIZE * 2, STUB.requestCount(), "4xx 期间请求应全部到达下游");
    }

    /**
     * 下游耗时 2 秒仍应正常返回。
     *
     * <p>这一条是在防回归：spring-cloud-circuitbreaker 的响应式实现会给每次调用套一层
     * {@code Mono.timeout()}，不显式配置 timelimiter 就落到 resilience4j 的默认值 1 秒，
     * 所有 /proxy 请求会被静默截断。配置写对了这个用例才过得去。</p>
     */
    @Test
    void shouldNotTimeOutAtResilience4jDefaultOneSecond() {
        STUB.respondAfter(Duration.ofSeconds(2));

        client.get().uri("/proxy/anything").exchange().expectStatus().isOk();

        assertEquals(1, STUB.requestCount());
        assertEquals(CircuitBreaker.State.CLOSED,
                circuitBreakerRegistry.circuitBreaker("bootDemoCircuitBreaker").getState());
    }

    /** 熔断的降级响应带上本次请求的 traceId，且不泄漏内部细节。 */
    @Test
    void shouldCarryTraceIdAndHideInternals() {
        String traceId = "circuitbreakertraceid0123456789a";

        STUB.respondWith(500);
        for (int i = 0; i < WINDOW_SIZE; i++) {
            client.get().uri("/proxy/anything").exchange().expectStatus().is5xxServerError();
        }

        byte[] body = client.get().uri("/proxy/anything")
                .header(TraceContext.TRACE_ID_HEADER, traceId)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(TraceContext.TRACE_ID_HEADER, traceId)
                .expectBody()
                .jsonPath("$.traceId").isEqualTo(traceId)
                .jsonPath("$.message").isNotEmpty()
                .returnResult()
                .getResponseBody();

        DegradeResponseAssertions.assertNoInternalDetails(body);
    }

}
