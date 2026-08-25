package com.example.template.config;

import org.springframework.boot.autoconfigure.validation.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jakarta Validation 校验配置，统一开启 Hibernate Validator 的快速失败模式。
 * <p>
 * 快速失败只保证一次校验在发现首个约束违规后停止，不保证多个非法字段之间固定的发现顺序。
 */
@Configuration(proxyBeanMethods = false)
public class ValidationConfig {

    /**
     * 定制 Spring Boot 自动配置的校验器，开启发现首项违规后立即停止的行为。
     *
     * @return 校验配置定制器
     */
    @Bean
    public ValidationConfigurationCustomizer failFastValidationCustomizer() {
        return configuration -> configuration.addProperty("hibernate.validator.fail_fast", "true");
    }
}
