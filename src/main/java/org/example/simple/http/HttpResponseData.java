package org.example.simple.http;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 完整缓冲并与底层连接解耦的 HTTP 响应。
 *
 * @param statusCode HTTP 状态码
 * @param headers 响应头，名称可对应多个值
 * @param body 响应体字节
 */
public record HttpResponseData(int statusCode, Map<String, List<String>> headers, byte[] body) {

    /**
     * 创建不可变响应数据。
     *
     * @param statusCode HTTP 状态码
     * @param headers 响应头
     * @param body 响应体字节
     */
    public HttpResponseData {
        if (statusCode < 100 || statusCode > 999) {
            throw new IllegalArgumentException("HTTP 状态码必须位于 100 到 999 之间");
        }
        headers = copyHeaders(headers);
        body = Objects.requireNonNull(body, "响应体字节不能为空").clone();
    }

    /**
     * 返回响应体字节副本。
     *
     * @return 响应体字节副本
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /**
     * 使用 UTF-8 将响应体转换为文本。
     *
     * @return UTF-8 响应文本
     */
    public String bodyAsString() {
        return bodyAsString(StandardCharsets.UTF_8);
    }

    /**
     * 使用指定字符集将响应体转换为文本。
     *
     * @param charset 响应字符集
     * @return 响应文本
     */
    public String bodyAsString(Charset charset) {
        return new String(body, Objects.requireNonNull(charset, "响应字符集不能为空"));
    }

    private static Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
        Objects.requireNonNull(source, "响应头不能为空");
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((name, values) -> {
            String validatedName = Objects.requireNonNull(name, "响应头名称不能为空");
            List<String> validatedValues = List.copyOf(Objects.requireNonNull(values, "响应头值列表不能为空"));
            result.put(validatedName, validatedValues);
        });
        return Map.copyOf(result);
    }
}
