package org.example.simple.http;

import java.time.Duration;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 单个 {@link HttpClients} 实例共享的不可变配置。
 *
 * @param connectTimeout 连接建立超时
 * @param requestTimeout 连接池获取和响应等待超时
 * @param skipSslVerification 是否跳过证书链与主机名校验
 * @param objectMapper HTTP JSON 转换使用的映射器
 */
public record HttpClientConfig(
    Duration connectTimeout,
    Duration requestTimeout,
    boolean skipSslVerification,
    ObjectMapper objectMapper) {

    /** 默认连接建立超时。 */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** 默认连接池获取和响应等待超时。 */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 校验并创建客户端配置。
     *
     * @param connectTimeout 连接建立超时
     * @param requestTimeout 连接池获取和响应等待超时
     * @param skipSslVerification 是否跳过 SSL 校验
     * @param objectMapper JSON 映射器
     */
    public HttpClientConfig {
        connectTimeout = HttpTimeouts.requireValid(connectTimeout, "连接超时");
        requestTimeout = HttpTimeouts.requireValid(requestTimeout, "请求超时");
        objectMapper = Objects.requireNonNull(objectMapper, "JSON 映射器不能为空");
    }

    /**
     * 创建使用严格 SSL 校验、五秒连接超时、五秒请求超时和默认 JSON 规则的配置。
     *
     * @return 默认配置
     */
    public static HttpClientConfig defaults() {
        return new HttpClientConfig(
            DEFAULT_CONNECT_TIMEOUT,
            DEFAULT_REQUEST_TIMEOUT,
            false,
            createDefaultObjectMapper());
    }

    /**
     * 创建配置构建器。
     *
     * @return 配置构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    private static ObjectMapper createDefaultObjectMapper() {
        return JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.ALL_NON_NULL)
            .build();
    }

    /**
     * HTTP 客户端配置构建器。
     */
    public static final class Builder {

        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private boolean skipSslVerification;
        private ObjectMapper objectMapper = createDefaultObjectMapper();

        private Builder() {
        }

        /**
         * 设置连接建立超时。
         *
         * @param connectTimeout 连接建立超时
         * @return 当前构建器
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * 设置连接池获取和响应等待超时。
         *
         * @param requestTimeout 请求超时
         * @return 当前构建器
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /**
         * 设置是否跳过证书链与主机名校验。
         * <p>
         * 启用后连接可能遭受中间人攻击，仅应用于受控测试或可信内网环境。
         *
         * @param skipSslVerification 是否跳过 SSL 校验
         * @return 当前构建器
         */
        public Builder skipSslVerification(boolean skipSslVerification) {
            this.skipSslVerification = skipSslVerification;
            return this;
        }

        /**
         * 设置 HTTP JSON 转换使用的映射器。
         *
         * @param objectMapper JSON 映射器
         * @return 当前构建器
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * 构建不可变客户端配置。
         *
         * @return 客户端配置
         */
        public HttpClientConfig build() {
            return new HttpClientConfig(connectTimeout, requestTimeout, skipSslVerification, objectMapper);
        }
    }
}
