package com.example.template.response;

import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证统一响应工具对业务字段、链路追踪标识和响应时间戳的填充规则。
 */
class RestResultUtilsTest {

    @AfterEach
    void clearThreadContext() {
        ThreadContext.clearAll();
    }

    @Test
    void shouldCreateSuccessResultWithDataAndCurrentTraceId() {
        ThreadContext.put("traceId", "test-trace-id");
        long before = System.currentTimeMillis();

        RestResult<String> result = RestResultUtils.success("payload");

        long after = System.currentTimeMillis();
        assertThat(result.code()).isZero();
        assertThat(result.data()).isEqualTo("payload");
        assertThat(result.message()).isEqualTo("成功");
        assertThat(result.traceId()).isEqualTo("test-trace-id");
        assertThat(result.timestamp()).isBetween(before, after);
    }

    @Test
    void shouldCreateSuccessResultWithoutData() {
        RestResult<Void> result = RestResultUtils.success();

        assertThat(result.code()).isZero();
        assertThat(result.data()).isNull();
        assertThat(result.message()).isEqualTo("成功");
        assertThat(result.traceId()).isNull();
        assertThat(result.timestamp()).isNotNull();
    }

    @Test
    void shouldCreateFailureResultWithSpecifiedError() {
        ThreadContext.put("traceId", "failure-trace-id");
        long before = System.currentTimeMillis();

        RestResult<Void> result = RestResultUtils.failure(400, "请求参数错误");

        long after = System.currentTimeMillis();
        assertThat(result.code()).isEqualTo(400);
        assertThat(result.data()).isNull();
        assertThat(result.message()).isEqualTo("请求参数错误");
        assertThat(result.traceId()).isEqualTo("failure-trace-id");
        assertThat(result.timestamp()).isBetween(before, after);
    }

    @Test
    void shouldTreatBlankTraceIdAsMissing() {
        ThreadContext.put("traceId", "   ");

        RestResult<Void> result = RestResultUtils.success();

        assertThat(result.traceId()).isNull();
    }
}
