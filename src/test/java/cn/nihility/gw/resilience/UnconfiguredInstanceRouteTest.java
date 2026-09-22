package cn.nihility.gw.resilience;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 一条引用了「配置里根本不存在的限流实例名」的路由，仍然要受保护。
 *
 * <p>这是 {@code configs.default} 存在的意义：新增路由时忘了加 resilience4j 配置，结果应该是
 * 套用项目默认配额，而不是无保护地裸奔、也不是启动失败。</p>
 *
 * <p>本类用测试属性整体替换了路由列表——YAML 的 list 属性不会跨配置源合并，所以这里声明的是
 * 唯一一条路由，与 application.yaml 中的 proxy-boot-demo 无关。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                // 整条路由只挂一个限流过滤器，实例名故意不在 application.yaml 的 instances 里
                "spring.cloud.gateway.server.webflux.routes[0].id=unconfigured-instance-route",
                "spring.cloud.gateway.server.webflux.routes[0].uri=lb://BootDemo",
                "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/unconfigured/**",
                "spring.cloud.gateway.server.webflux.routes[0].filters[0]"
                        + "=Resilience4jRateLimiter=instanceThatIsNotConfigured",
                "spring.cloud.gateway.server.webflux.routes[0].filters[1]=StripPrefix=1",
                // 把默认配额压到 2，好在秒级内跑完
                "resilience4j.ratelimiter.configs.default.limit-for-period=2",
                "resilience4j.ratelimiter.configs.default.limit-refresh-period=1s"
        })
class UnconfiguredInstanceRouteTest {

    /** 默认配额，与上面 configs.default 的 limit-for-period 一致。 */
    private static final int DEFAULT_QUOTA = 2;

    /** 限流刷新周期。 */
    private static final Duration REFRESH_PERIOD = Duration.ofSeconds(1);

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

    private WebTestClient client;

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

    /** 实例名没配置，但路由照样受默认配额保护：超出即 429，且被拦下的请求不穿透下游。 */
    @Test
    void shouldStillBeRateLimitedWithDefaultQuota() {
        for (int i = 0; i < DEFAULT_QUOTA; i++) {
            client.get().uri("/unconfigured/anything").exchange().expectStatus().isOk();
        }
        assertEquals(DEFAULT_QUOTA, STUB.requestCount(), "默认配额内的请求应正常转发");

        client.get().uri("/unconfigured/anything").exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.reason").isEqualTo(DegradeResponseWriter.REASON_RATE_LIMITED);

        assertEquals(DEFAULT_QUOTA, STUB.requestCount(), "被限流的请求不能穿透到下游");
    }

}
