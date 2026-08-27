package org.example.simple.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 不可变 HTTP 请求描述。
 */
public final class HttpRequestData {

    private final HttpMethod method;
    private final String url;
    private final Map<String, List<String>> headers;
    private final Map<String, List<String>> queryParameters;
    private final HttpRequestBody body;
    private final Duration timeout;

    private HttpRequestData(Builder builder) {
        method = Objects.requireNonNull(builder.method, "HTTP 方法不能为空");
        url = validateUrl(builder.url);
        headers = copyValues(builder.headers, "请求头");
        queryParameters = copyValues(builder.queryParameters, "查询参数");
        body = builder.body;
        timeout = builder.timeout == null ? null : HttpTimeouts.requireValid(builder.timeout, "单次请求超时");
        if (method == HttpMethod.GET && body != null) {
            throw new IllegalArgumentException("GET 请求不能包含请求体");
        }
    }

    /**
     * 创建 GET 请求构建器。
     *
     * @param url HTTP 或 HTTPS URL 文本，例如 {@code http://127.0.0.1:23456/hello}
     * @return 请求构建器
     */
    public static Builder get(String url) {
        return new Builder(HttpMethod.GET, url);
    }

    /**
     * 创建 POST 请求构建器。
     *
     * @param url HTTP 或 HTTPS URL 文本，例如 {@code http://127.0.0.1:23456/hello}
     * @return 请求构建器
     */
    public static Builder post(String url) {
        return new Builder(HttpMethod.POST, url);
    }

    /**
     * 创建 PUT 请求构建器。
     *
     * @param url HTTP 或 HTTPS URL 文本，例如 {@code http://127.0.0.1:23456/hello}
     * @return 请求构建器
     */
    public static Builder put(String url) {
        return new Builder(HttpMethod.PUT, url);
    }

    /**
     * 创建 DELETE 请求构建器。
     *
     * @param url HTTP 或 HTTPS URL 文本，例如 {@code http://127.0.0.1:23456/hello}
     * @return 请求构建器
     */
    public static Builder delete(String url) {
        return new Builder(HttpMethod.DELETE, url);
    }

    /** @return HTTP 方法 */
    public HttpMethod method() {
        return method;
    }

    /** @return HTTP 或 HTTPS URL 文本 */
    public String url() {
        return url;
    }

    /** @return 不可变请求头 */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /** @return 不可变查询参数 */
    public Map<String, List<String>> queryParameters() {
        return queryParameters;
    }

    /** @return 可选请求体 */
    public Optional<HttpRequestBody> body() {
        return Optional.ofNullable(body);
    }

    /** @return 可选单次请求超时 */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    private static String validateUrl(String url) {
        String validatedUrl = FormRequestBody.requireText(url, "请求 URL 不能为空");
        final URI uri;
        try {
            uri = new URI(validatedUrl);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("请求 URL 格式不合法: " + validatedUrl, exception);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("请求 URL 仅支持 HTTP 或 HTTPS 协议");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("请求 URL 必须包含主机名");
        }
        return validatedUrl;
    }

    private static Map<String, List<String>> copyValues(
        Map<String, List<String>> source,
        String description) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((name, values) -> {
            String validatedName = FormRequestBody.requireText(name, description + "名称不能为空");
            List<String> validatedValues = List.copyOf(values);
            validatedValues.forEach(value -> Objects.requireNonNull(value, description + "值不能为空"));
            result.put(validatedName, validatedValues);
        });
        return Map.copyOf(result);
    }

    /**
     * HTTP 请求构建器。
     */
    public static final class Builder {

        private final HttpMethod method;
        private final String url;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private final Map<String, List<String>> queryParameters = new LinkedHashMap<>();
        private HttpRequestBody body;
        private Duration timeout;

        private Builder(HttpMethod method, String url) {
            this.method = method;
            this.url = url;
        }

        /**
         * 增加请求头，不覆盖同名已有值。
         *
         * @param name 请求头名称
         * @param value 请求头值
         * @return 当前构建器
         */
        public Builder header(String name, String value) {
            addValue(headers, name, value, "请求头");
            return this;
        }

        /**
         * 增加 URL 查询参数，不覆盖同名已有值。
         *
         * @param name 参数名称
         * @param value 参数值
         * @return 当前构建器
         */
        public Builder queryParameter(String name, String value) {
            addValue(queryParameters, name, value, "查询参数");
            return this;
        }

        /**
         * 设置请求体。
         *
         * @param body 请求体
         * @return 当前构建器
         */
        public Builder body(HttpRequestBody body) {
            this.body = Objects.requireNonNull(body, "请求体不能为空");
            return this;
        }

        /**
         * 设置仅作用于当前请求的超时。
         *
         * @param timeout 单次请求超时
         * @return 当前构建器
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * 构建不可变请求。
         *
         * @return HTTP 请求
         */
        public HttpRequestData build() {
            return new HttpRequestData(this);
        }

        private static void addValue(
            Map<String, List<String>> target,
            String name,
            String value,
            String description) {
            String validatedName = FormRequestBody.requireText(name, description + "名称不能为空");
            String validatedValue = Objects.requireNonNull(value, description + "值不能为空");
            target.computeIfAbsent(validatedName, ignored -> new ArrayList<>()).add(validatedValue);
        }
    }
}
