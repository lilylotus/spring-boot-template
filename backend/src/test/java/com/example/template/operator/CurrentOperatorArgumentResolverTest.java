package com.example.template.operator;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 operator-header-identity 变更 tasks.md 2.2 要求的场景：请求头齐全（含 {@code X-User-Name}
 * 百分号编码解码还原）、请求头缺失回退默认值、{@code X-User-Name} 单独缺失时不报错。
 */
class CurrentOperatorArgumentResolverTest {

    private final CurrentOperatorArgumentResolver resolver = new CurrentOperatorArgumentResolver();

    @Test
    void resolveArgument_headersPresent_usesHeaderValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "43");
        // 前端用 encodeURIComponent("张三") 编码后传输，后端需解码还原。
        request.addHeader("X-User-Name", "%E5%BC%A0%E4%B8%89");

        OperatorContext operator = (OperatorContext) resolver.resolveArgument(
                null, null, new ServletWebRequest(request), null);

        assertThat(operator.userId()).isEqualTo("43");
        assertThat(operator.userName()).isEqualTo("张三");
    }

    @Test
    void resolveArgument_userNameMissing_userIdPresent_userNameIsNull() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "43");

        OperatorContext operator = (OperatorContext) resolver.resolveArgument(
                null, null, new ServletWebRequest(request), null);

        assertThat(operator.userId()).isEqualTo("43");
        assertThat(operator.userName()).isNull();
    }

    @Test
    void resolveArgument_userNameInvalidPercentEncoding_fallsBackToRawValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "43");
        request.addHeader("X-User-Name", "invalid%zz");

        OperatorContext operator = (OperatorContext) resolver.resolveArgument(
                null, null, new ServletWebRequest(request), null);

        assertThat(operator.userId()).isEqualTo("43");
        assertThat(operator.userName()).isEqualTo("invalid%zz");
    }

    @Test
    void resolveArgument_userIdMissing_fallsBackToDefaultOperator() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        OperatorContext operator = (OperatorContext) resolver.resolveArgument(
                null, null, new ServletWebRequest(request), null);

        assertThat(operator.userId()).isEqualTo(DefaultOperator.ID);
        assertThat(operator.userName()).isEqualTo(DefaultOperator.NAME);
    }

    @Test
    void resolveArgument_userIdBlank_fallsBackToDefaultOperator() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "   ");
        request.addHeader("X-User-Name", "张三");

        OperatorContext operator = (OperatorContext) resolver.resolveArgument(
                null, null, new ServletWebRequest(request), null);

        assertThat(operator.userId()).isEqualTo(DefaultOperator.ID);
        assertThat(operator.userName()).isEqualTo(DefaultOperator.NAME);
    }
}
