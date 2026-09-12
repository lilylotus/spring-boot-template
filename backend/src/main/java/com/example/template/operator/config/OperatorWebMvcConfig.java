package com.example.template.operator.config;

import com.example.template.operator.CurrentOperatorArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 注册 {@link CurrentOperatorArgumentResolver}，使 {@code @CurrentOperator} 标注的
 * Controller 方法参数在全局范围内可用（见 operator-header-identity 变更 design.md D1）。
 */
@Configuration
public class OperatorWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentOperatorArgumentResolver());
    }
}
