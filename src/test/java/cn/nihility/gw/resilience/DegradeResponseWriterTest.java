package cn.nihility.gw.resilience;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DegradeResponseWriter} 的响应体构造。
 *
 * <p>重点是 traceId 缺失时字段仍然存在（以空串占位），调用方不需要为「有没有这个字段」写分支。</p>
 */
class DegradeResponseWriterTest {

    private final DegradeResponseWriter writer = new DegradeResponseWriter(new ObjectMapper());

    /** 有 traceId 时原样带上。 */
    @Test
    void shouldCarryTraceIdWhenPresent() {
        Map<String, Object> body = writer.body(
                DegradeResponseWriter.REASON_RATE_LIMITED, "请求过于频繁", "abc123");

        assertEquals(DegradeResponseWriter.REASON_RATE_LIMITED, body.get("reason"));
        assertEquals("请求过于频繁", body.get("message"));
        assertEquals("abc123", body.get("traceId"));
    }

    /** traceId 为 null 或空白时以空串占位，而不是让字段消失。 */
    @Test
    void shouldFallBackToEmptyTraceId() {
        assertEquals("", writer.body("R", "m", null).get("traceId"));
        assertEquals("", writer.body("R", "m", "   ").get("traceId"));
    }

    /** 字段顺序固定，便于人工比对两种拦截的响应。 */
    @Test
    void shouldKeepFieldOrderStable() {
        Map<String, Object> body = writer.body(
                DegradeResponseWriter.REASON_CIRCUIT_BREAKER_OPEN, "下游不可用", "t1");

        assertEquals(List.of("reason", "message", "traceId"), List.copyOf(body.keySet()));
        assertEquals(3, body.size(), "降级响应体只应有这三个字段，实际: " + body);
    }

}
