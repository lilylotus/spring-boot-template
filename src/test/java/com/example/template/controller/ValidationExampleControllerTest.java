package com.example.template.controller;

import java.math.BigDecimal;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.template.config.ValidationConfig;
import com.example.template.exception.GlobalExceptionHandler;
import com.example.template.log4j2.TraceIdFilter;
import com.example.template.validation.ValidationExampleRequest;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证参数校验示例、快速失败配置和全局异常响应在 Spring MVC 层的协作行为。
 */
@WebMvcTest(controllers = ValidationExampleController.class)
@ContextConfiguration(classes = {
    ValidationExampleController.class,
    ValidationConfig.class,
    GlobalExceptionHandler.class,
    TraceIdFilter.class,
    ValidationExampleControllerTest.FailingController.class
})
class ValidationExampleControllerTest {

    private static final String TRACE_ID = "mvc-test-trace-id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Validator validator;

    @Test
    void shouldReturnUnifiedSuccessResultForValidRequest() throws Exception {
        mockMvc.perform(post("/api/validation/example")
                .header("X-Trace-Id", TRACE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest().toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Trace-Id", TRACE_ID))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.patternValue").value("AB1234"))
            .andExpect(jsonPath("$.message").value("成功"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))
            .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @ParameterizedTest
    @MethodSource("invalidConstraintCases")
    void shouldHandleEveryConstraintWithItsChineseMessage(
        String fieldName,
        JsonNode invalidValue,
        String expectedMessage) throws Exception {
        ObjectNode request = validRequest();
        request.set(fieldName, invalidValue);

        mockMvc.perform(post("/api/validation/example")
                .header("X-Trace-Id", TRACE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.data").value(nullValue()))
            .andExpect(jsonPath("$.message").value(expectedMessage))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))
            .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void shouldStopValidationAfterFirstViolation() throws Exception {
        ObjectNode request = validRequest();
        request.putNull("notNullValue");
        request.put("notBlankValue", " ");
        ValidationExampleRequest invalidRequest = objectMapper.treeToValue(request, ValidationExampleRequest.class);

        org.assertj.core.api.Assertions.assertThat(validator.validate(invalidRequest)).hasSize(1);
    }

    @Test
    void shouldHandleMethodParameterValidation() throws Exception {
        mockMvc.perform(get("/api/validation/positive")
                .header("X-Trace-Id", TRACE_ID)
                .param("value", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("value必须为正数"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void shouldReturnUnifiedSuccessResultForValidFormRequest() throws Exception {
        mockMvc.perform(put("/api/validation/form")
                .header("X-Trace-Id", TRACE_ID)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "zhangsan")
                .param("email", "zhangsan@example.com")
                .param("age", "30"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Trace-Id", TRACE_ID))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.username").value("zhangsan"))
            .andExpect(jsonPath("$.data.email").value("zhangsan@example.com"))
            .andExpect(jsonPath("$.data.age").value(30))
            .andExpect(jsonPath("$.message").value("成功"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))
            .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @ParameterizedTest
    @MethodSource("invalidFormCases")
    void shouldHandleInvalidFormField(String fieldName, String invalidValue, String expectedMessage) throws Exception {
        MockHttpServletRequestBuilder request = put("/api/validation/form")
            .header("X-Trace-Id", TRACE_ID)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("username", fieldName.equals("username") ? invalidValue : "zhangsan")
            .param("email", fieldName.equals("email") ? invalidValue : "zhangsan@example.com")
            .param("age", fieldName.equals("age") ? invalidValue : "30");

        mockMvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.data").value(nullValue()))
            .andExpect(jsonPath("$.message").value(expectedMessage))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))
            .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void shouldHandleUnsupportedFormContentType() throws Exception {
        mockMvc.perform(put("/api/validation/form")
                .header("X-Trace-Id", TRACE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(header().string("X-Trace-Id", TRACE_ID))
            .andExpect(jsonPath("$.code").value(415))
            .andExpect(jsonPath("$.data").value(nullValue()))
            .andExpect(jsonPath("$.message").value("请求媒体类型不支持"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))
            .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void shouldHandleMissingRequestParameter() throws Exception {
        mockMvc.perform(get("/api/validation/positive")
                .header("X-Trace-Id", TRACE_ID))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("缺少必填参数: value"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void shouldHandleRequestParameterTypeMismatch() throws Exception {
        mockMvc.perform(get("/api/validation/positive")
                .header("X-Trace-Id", TRACE_ID)
                .param("value", "not-a-number"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("参数类型错误: value"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void shouldHandleUnreadableRequestBody() throws Exception {
        mockMvc.perform(post("/api/validation/example")
                .header("X-Trace-Id", TRACE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid-json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("请求体格式错误"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void shouldHideUnexpectedExceptionDetails() throws Exception {
        mockMvc.perform(get("/test/unexpected-error")
                .header("X-Trace-Id", TRACE_ID))
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("X-Trace-Id", TRACE_ID))
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.data").value(nullValue()))
            .andExpect(jsonPath("$.message").value("服务器内部错误"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))
            .andExpect(jsonPath("$.timestamp").isNumber());
    }

    static Stream<Arguments> invalidConstraintCases() {
        JsonNodeFactory nodeFactory = JsonNodeFactory.instance;
        return Stream.of(
            arguments("notNullValue", nodeFactory.nullNode(), "notNullValue不能为null"),
            arguments("notEmptyValue", nodeFactory.arrayNode(), "notEmptyValue不能为空"),
            arguments("notBlankValue", nodeFactory.textNode(" "), "notBlankValue不能为空白"),
            arguments("sizeValue", nodeFactory.textNode("x"), "sizeValue长度必须在2到10之间"),
            arguments("minValue", nodeFactory.numberNode(0), "minValue不能小于1"),
            arguments("maxValue", nodeFactory.numberNode(101), "maxValue不能大于100"),
            arguments("positiveValue", nodeFactory.numberNode(0), "positiveValue必须为正数"),
            arguments("negativeValue", nodeFactory.numberNode(0), "negativeValue必须为负数"),
            arguments("emailValue", nodeFactory.textNode("invalid-email"), "emailValue必须是合法邮箱"),
            arguments("patternValue", nodeFactory.textNode("invalid"), "patternValue必须为两个大写字母加四位数字"),
            arguments("pastValue", nodeFactory.textNode("2999-01-01"), "pastValue必须是过去日期"),
            arguments("futureValue", nodeFactory.textNode("2000-01-01"), "futureValue必须是未来日期"),
            arguments("decimalMinValue", nodeFactory.numberNode(new BigDecimal("0.00")), "decimalMinValue不能小于0.01"),
            arguments("decimalMaxValue", nodeFactory.numberNode(new BigDecimal("10000.00")), "decimalMaxValue不能大于9999.99")
        );
    }

    static Stream<Arguments> invalidFormCases() {
        return Stream.of(
            arguments("username", "x", "username长度必须在2到20之间"),
            arguments("email", "invalid-email", "email必须是合法邮箱"),
            arguments("age", "17", "age不能小于18")
        );
    }

    private ObjectNode validRequest() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("notNullValue", "存在的值");
        request.putArray("notEmptyValue").add("item");
        request.put("notBlankValue", "有效文本");
        request.put("sizeValue", "abcd");
        request.put("minValue", 1);
        request.put("maxValue", 100);
        request.put("positiveValue", 1);
        request.put("negativeValue", -1);
        request.put("emailValue", "user@example.com");
        request.put("patternValue", "AB1234");
        request.put("pastValue", "2000-01-01");
        request.put("futureValue", "2999-01-01");
        request.put("decimalMinValue", new BigDecimal("0.01"));
        request.put("decimalMaxValue", new BigDecimal("9999.99"));
        return request;
    }

    /**
     * 测试专用控制器，用于触发未预期异常并验证全局兜底响应。
     */
    @RestController
    static class FailingController {

        /**
         * 抛出包含内部细节的异常，供测试确认响应不会泄露该细节。
         */
        @GetMapping("/test/unexpected-error")
        public void throwUnexpectedException() {
            throw new IllegalStateException("不应暴露的内部细节");
        }
    }
}
