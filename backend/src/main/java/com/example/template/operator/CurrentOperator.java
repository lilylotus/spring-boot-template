package com.example.template.operator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在 Controller 方法参数上，表示该参数由 {@link CurrentOperatorArgumentResolver}
 * 自动解析为当前请求的 {@link OperatorContext}，用法与 {@code @RequestBody} 等 Spring MVC
 * 内置参数解析机制处于同一层次（见 operator-header-identity 变更 design.md D1）。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentOperator {
}
