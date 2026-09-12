package com.example.template.operator;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析 {@link CurrentOperator} 标注的方法参数：从请求头 {@code X-User-Id}/{@code X-User-Name}
 * 读取当前操作人，{@code X-User-Id} 缺失或空白时整体回退 {@link DefaultOperator}（不是字段级部分
 * 回退），不查询数据库、不做任何校验（见 operator-header-identity 变更 design.md D1/D2）。
 *
 * <p>{@code X-User-Name} 的值可能包含中文，浏览器端只能以 ISO-8859-1 范围字符写入请求头，
 * 因此前端会先用 {@code encodeURIComponent} 做百分号编码，此处对应用 {@link URLDecoder} 以
 * UTF-8 解码还原；解码失败时不影响整体请求，直接回退使用原始未解码的值（见 design.md D4 的
 * “实现落地说明”）。{@code X-User-Id} 约定始终是数字 ID 字符串，不含非 ASCII 字符，不需要解码。
 */
public class CurrentOperatorArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USER_NAME = "X-User-Name";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentOperator.class)
                && OperatorContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                    NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String userId = webRequest.getHeader(HEADER_USER_ID);
        if (userId == null || userId.isBlank()) {
            return new OperatorContext(DefaultOperator.ID, DefaultOperator.NAME);
        }
        String userName = webRequest.getHeader(HEADER_USER_NAME);
        return new OperatorContext(userId, decodeUserName(userName));
    }

    private static String decodeUserName(String userName) {
        if (userName == null) {
            return null;
        }
        try {
            return URLDecoder.decode(userName, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return userName;
        }
    }
}
