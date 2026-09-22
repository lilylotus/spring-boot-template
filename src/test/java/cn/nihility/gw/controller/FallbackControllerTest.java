package cn.nihility.gw.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import cn.nihility.gw.resilience.DegradeResponseWriter;
import cn.nihility.gw.trace.TraceContext;

/**
 * {@link FallbackController} 的独立测试，不起完整应用上下文。
 */
class FallbackControllerTest {

    private final WebTestClient client = WebTestClient
            .bindToController(new FallbackController(new DegradeResponseWriter(new ObjectMapper())))
            .build();

    /** 降级接口返回 503 + 约定的 JSON 结构。 */
    @Test
    void shouldReturnServiceUnavailableWithDegradeBody() {
        client.get().uri("/fallback/boot-demo").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody()
                .jsonPath("$.reason").isEqualTo(DegradeResponseWriter.REASON_CIRCUIT_BREAKER_OPEN)
                .jsonPath("$.message").isNotEmpty()
                .jsonPath("$.traceId").exists();
    }

    /** forward 会沿用原始请求的方法，被熔断的 POST 也要能落到这里。 */
    @Test
    void shouldAcceptAnyHttpMethod() {
        client.post().uri("/fallback/boot-demo").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.reason").isEqualTo(DegradeResponseWriter.REASON_CIRCUIT_BREAKER_OPEN);
    }

    /** 请求头里带 traceId 时，降级响应要把它带回去。 */
    @Test
    void shouldEchoTraceIdFromRequestHeader() {
        client.get().uri("/fallback/boot-demo")
                .header(TraceContext.TRACE_ID_HEADER, "fallbacktraceid0123456789abcdef")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.traceId").isEqualTo("fallbacktraceid0123456789abcdef");
    }

}
