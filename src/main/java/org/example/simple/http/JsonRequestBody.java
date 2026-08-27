package org.example.simple.http;

import java.util.Objects;

/**
 * 由 Jackson 序列化的 JSON 请求体。
 *
 * @param value 待序列化对象
 */
public record JsonRequestBody(Object value) implements HttpRequestBody {

    /**
     * 创建 JSON 请求体。
     *
     * @param value 待序列化对象
     */
    public JsonRequestBody {
        value = Objects.requireNonNull(value, "JSON 请求对象不能为空");
    }
}
