package cn.nihility.gw.controller;

import cn.nihility.gw.feign.Template4Client;
import cn.nihility.gw.trace.TraceContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

/**
 * 验证 Controller / Service 改为响应式之后的行为。
 *
 * <p>重点是两件事：阻塞的 Feign 调用确实被挪出了事件循环线程，以及跨线程之后 traceId 没丢。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"
        })
class WelcomeControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private Template4Client template4Client;

    /** 本地接口应正常返回 Mono 包装的结果。 */
    @Test
    void shouldReturnWelcomeReactively() {
        webTestClient.get().uri("/welcome")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.message").isEqualTo("WelcomeSuccess");
    }

    /**
     * 阻塞的 Feign 调用必须跑在 boundedElastic 线程上，且 traceId 要跟着过去。
     *
     * <p>断言直接在打桩方法内部抓取执行线程和 MDC，比事后解析日志更精确。</p>
     */
    @Test
    void shouldOffloadFeignCallAndKeepTraceId() {
        String traceId = "welcometraceid0123456789abcdef01";
        AtomicReference<String> callThread = new AtomicReference<>();
        AtomicReference<String> callTraceId = new AtomicReference<>();

        given(template4Client.welcome()).willAnswer(invocation -> {
            callThread.set(Thread.currentThread().getName());
            callTraceId.set(MDC.get(TraceContext.TRACE_ID));
            return Map.of("message", "FromDownstream");
        });

        webTestClient.get().uri("/feign/welcome")
                .header(TraceContext.TRACE_ID_HEADER, traceId)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(TraceContext.TRACE_ID_HEADER, traceId)
                .expectBody()
                .jsonPath("$.message").isEqualTo("FromDownstream");

        assertNotNull(callThread.get(), "Feign 打桩方法没有被调用");
        assertTrue(
                callThread.get().contains("boundedElastic"),
                "阻塞调用应跑在 boundedElastic 线程池，实际线程: " + callThread.get());
        assertEquals(traceId, callTraceId.get(), "跨线程之后 traceId 丢失");
    }

}
