package com.example.template.util.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 使用 UTF-8 编码的 {@code application/x-www-form-urlencoded} 请求体。
 *
 * @param fields 表单字段，一个名称可对应多个值
 */
public record FormRequestBody(Map<String, List<String>> fields) implements HttpRequestBody {

    /**
     * 创建 URL 编码表单请求体。
     *
     * @param fields 表单字段
     */
    public FormRequestBody {
        fields = copyValues(fields, "表单");
    }

    /**
     * 从单值字段创建表单请求体。
     *
     * @param fields 单值表单字段
     * @return 表单请求体
     */
    public static FormRequestBody of(Map<String, String> fields) {
        Objects.requireNonNull(fields, "表单字段不能为空");
        Map<String, List<String>> values = new LinkedHashMap<>();
        fields.forEach((name, value) -> values.put(name, List.of(value)));
        return new FormRequestBody(values);
    }

    static Map<String, List<String>> copyValues(Map<String, List<String>> source, String description) {
        Objects.requireNonNull(source, description + "字段不能为空");
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((name, values) -> {
            String validatedName = requireText(name, description + "字段名称不能为空");
            List<String> validatedValues = List.copyOf(
                Objects.requireNonNull(values, description + "字段值列表不能为空"));
            if (validatedValues.isEmpty()) {
                throw new IllegalArgumentException(description + "字段值列表不能为空");
            }
            validatedValues.forEach(value -> Objects.requireNonNull(value, description + "字段值不能为空"));
            result.put(validatedName, validatedValues);
        });
        return Map.copyOf(result);
    }

    static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
