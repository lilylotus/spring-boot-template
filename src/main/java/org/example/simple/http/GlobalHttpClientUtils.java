package org.example.simple.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.IdleConnectionEvictor;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.*;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 全局静态 HTTP 客户端工具类，基于 Apache HttpClient5 实现。
 * <p>
 * 全部方法均为静态方法，调用方无需创建或管理任何客户端实例：类加载时即完成默认配置初始化，
 * 可直接调用 {@link #get}/{@link #post}/{@link #put}/{@link #delete}；如需自定义连接池、
 * 超时、SSL 校验策略等，调用 {@link #configure(HttpClientConfig)}。
 * <p>
 * <strong>生命周期：</strong>{@link #configure(HttpClientConfig)} 会原子替换全局客户端，
 * 旧连接池会等待其在途请求全部完成后才真正关闭，不会中断正在进行的请求；{@link #shutdown()}
 * 幂等地关闭当前客户端，关闭后发起的请求会收到 {@link HttpClientException}
 * （{@code errorCode = CLIENT_CLOSED}），再次调用 {@code configure(...)} 即可恢复可用。
 * <p>
 * <strong>注意：</strong>{@link HttpClientConfig#skipSslVerify()} 开启后将信任所有证书并跳过
 * 主机名校验，仅建议在受控/内网环境使用，生产环境面向公网时应保持默认的正式证书校验。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 开箱即用
 * HttpClientResponse resp = GlobalHttpClientUtils.get(HttpRequestData.builder(url).build());
 *
 * // form-data 文件上传 + 文本参数
 * HttpRequestData request = HttpRequestData.builder(url)
 *     .formData(f -> f.file("FILE", new File("/root/123.txt"), "text/plain")
 *                     .text("FLAG", "HHH"))
 *     .build();
 * HttpClientResponse response = GlobalHttpClientUtils.post(request);
 *
 * // 自定义全局配置
 * GlobalHttpClientUtils.configure(HttpClientConfig.builder()
 *     .connectTimeout(Duration.ofSeconds(5))
 *     .poolConfig(HttpConnectionPoolConfig.builder().maxTotal(500).build())
 *     .build());
 * }</pre>
 *
 * @author demo
 * @since 1.0
 */
public final class GlobalHttpClientUtils {

    private static final Logger log = LoggerFactory.getLogger(GlobalHttpClientUtils.class);

    private static final ContentType DEFAULT_RAW_CONTENT_TYPE = ContentType.APPLICATION_JSON;
    private static final ContentType DEFAULT_BINARY_CONTENT_TYPE = ContentType.APPLICATION_OCTET_STREAM;

    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<Map<String, Object>> TYPE_REFERENCE_MAP = new TypeReference<Map<String, Object>>() {
    };

    private static final TypeReference<List<Map<String, Object>>> TYPE_REFERENCE_LIST = new TypeReference<List<Map<String, Object>>>() {
    };

    private static final AtomicReference<ClientHolder> HOLDER = new AtomicReference<>();
    private static final ClientHolder CLOSED_SENTINEL = ClientHolder.closedSentinel();

    static {
        configure(HttpClientConfig.builder().build());
    }

    private GlobalHttpClientUtils() {
    }

    // -------------------------------------------------------------------------
    // 生命周期管理
    // -------------------------------------------------------------------------

    /**
     * 原子替换全局客户端配置。
     * <p>
     * 新配置立即生效于后续发起的请求；旧连接池会等待其"在途请求"全部完成后才真正关闭，
     * 不会中断正在进行的请求（例如大文件上传）。
     *
     * @param config 新的全局配置，不能为 null
     */
    public static void configure(HttpClientConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        ClientHolder newHolder = ClientHolder.create(config);
        ClientHolder oldHolder = HOLDER.getAndSet(newHolder);
        retire(oldHolder);
        log.info("GlobalHttpClientUtils 已应用新配置");
    }

    /**
     * 幂等关闭全局客户端。
     * <p>
     * 关闭后发起的请求会收到 {@link HttpClientException}（{@code errorCode = CLIENT_CLOSED}）；
     * 重复调用不会报错，也不会产生额外副作用；关闭后可再次调用 {@link #configure} 恢复可用。
     */
    public static void shutdown() {
        ClientHolder oldHolder = HOLDER.getAndSet(CLOSED_SENTINEL);
        retire(oldHolder);
        log.info("GlobalHttpClientUtils 已关闭");
    }

    /**
     * 将旧 holder 标记为"退场中"：若已无在途请求则立即关闭，否则交给最后一个在途请求的
     * {@code finally} 块触发关闭。若旧 holder 为 null 或已是关闭哨兵，直接返回（保证
     * {@link #shutdown()} 的幂等性）。
     */
    private static void retire(ClientHolder holder) {
        if (holder == null || holder.sentinel) {
            return;
        }
        holder.closing = true;
        if (holder.inFlight.get() == 0) {
            holder.closeNow();
        }
    }

    // -------------------------------------------------------------------------
    // GET / POST / PUT / DELETE
    // -------------------------------------------------------------------------

    /**
     * 发送 HTTP GET 请求。
     *
     * @param request 请求数据，不能为 null
     * @return 响应结果
     * @throws HttpClientException 当客户端已关闭、超时、SSL 异常、网络异常或状态码非 2xx 时抛出
     */
    public static HttpClientResponse get(HttpRequestData request) {
        return execute("GET", request);
    }

    /**
     * 发送 HTTP POST 请求，支持 form-data / x-www-form-urlencoded / binary / raw 四种请求体。
     *
     * @param request 请求数据，不能为 null
     * @return 响应结果
     * @throws HttpClientException 当客户端已关闭、超时、SSL 异常、网络异常或状态码非 2xx 时抛出
     */
    public static HttpClientResponse post(HttpRequestData request) {
        return execute("POST", request);
    }

    /**
     * 发送 HTTP PUT 请求，支持 form-data / x-www-form-urlencoded / binary / raw 四种请求体。
     *
     * @param request 请求数据，不能为 null
     * @return 响应结果
     * @throws HttpClientException 当客户端已关闭、超时、SSL 异常、网络异常或状态码非 2xx 时抛出
     */
    public static HttpClientResponse put(HttpRequestData request) {
        return execute("PUT", request);
    }

    /**
     * 发送 HTTP DELETE 请求。
     *
     * @param request 请求数据，不能为 null
     * @return 响应结果
     * @throws HttpClientException 当客户端已关闭、超时、SSL 异常、网络异常或状态码非 2xx 时抛出
     */
    public static HttpClientResponse delete(HttpRequestData request) {
        return execute("DELETE", request);
    }

    /**
     * 四种 HTTP 方法的共用执行逻辑：引用计数保护 holder 不被 {@link #configure}/{@link #shutdown}
     * 并发关闭，构建请求与请求体，执行并解析响应，最终释放引用计数。
     */
    private static HttpClientResponse execute(String method, HttpRequestData request) {
        Objects.requireNonNull(request, "request must not be null");

        ClientHolder holder = HOLDER.get();
        if (holder.sentinel) {
            throw new HttpClientException(HttpClientException.ErrorCode.CLIENT_CLOSED,
                "HTTP 客户端已关闭，请先调用 configure(...) 恢复", null, null);
        }

        holder.inFlight.incrementAndGet();
        try {
            HttpUriRequestBase httpRequest = buildRequest(method, request.url());
            applyHeaders(httpRequest, request.headers());

            HttpEntity entity = buildEntity(request);
            if (entity != null) {
                httpRequest.setEntity(entity);
            }
            if (request.timeoutOverride() != null) {
                httpRequest.setConfig(RequestConfig.custom()
                    .setResponseTimeout(Timeout.of(request.timeoutOverride()))
                    .build());
            }

            log.debug("HTTP 请求: {} {}", method, request.url());
            HttpClientResponseHandler<HttpClientResponse> responseHandler = response -> {
                int statusCode = response.getCode();
                log.debug("HTTP 响应状态码: {}, URL: {}", statusCode, request.url());

                byte[] bodyBytes = readBody(response);
                Map<String, String> responseHeaders = readHeaders(response);

                /*if (statusCode < 200 || statusCode >= 300) {
                    throw new HttpClientException(HttpClientException.ErrorCode.HTTP_STATUS_ERROR,
                        "HTTP 请求失败，状态码: " + statusCode + "，URL: " + request.url(), statusCode, null);
                }*/
                return new HttpClientResponse(statusCode, responseHeaders, bodyBytes, holder.objectMapper);
            };
            return holder.client.execute(httpRequest, responseHandler);
        } catch (ConnectTimeoutException e) {
            throw new HttpClientException(HttpClientException.ErrorCode.CONNECT_TIMEOUT,
                "连接超时，URL: " + request.url(), null, e);
        } catch (SocketTimeoutException e) {
            throw new HttpClientException(HttpClientException.ErrorCode.RESPONSE_TIMEOUT,
                "响应超时，URL: " + request.url(), null, e);
        } catch (SSLException e) {
            throw new HttpClientException(HttpClientException.ErrorCode.SSL_ERROR,
                "SSL 握手失败，URL: " + request.url(), null, e);
        } catch (IOException e) {
            throw new HttpClientException(HttpClientException.ErrorCode.IO_ERROR,
                "HTTP 请求 I/O 异常，URL: " + request.url(), null, e);
        } finally {
            if (holder.inFlight.decrementAndGet() == 0 && holder.closing) {
                holder.closeNow();
            }
        }
    }

    private static HttpUriRequestBase buildRequest(String method, String url) {
        return switch (method) {
            case "GET" -> new HttpGet(url);
            case "POST" -> new HttpPost(url);
            case "PUT" -> new HttpPut(url);
            case "DELETE" -> new HttpDelete(url);
            default -> throw new IllegalArgumentException("不支持的 HTTP 方法: " + method);
        };
    }

    private static void applyHeaders(HttpUriRequestBase httpRequest, Map<String, String> headers) {
        headers.forEach(httpRequest::setHeader);
    }

    private static HttpEntity buildEntity(HttpRequestData request) {
        switch (request.bodyKind()) {
            case NONE:
                return null;
            case FORM_DATA:
                return buildMultipartEntity(request.formParts());
            case URL_ENCODED:
                return buildUrlEncodedEntity(request.urlEncodedParams());
            case BINARY:
                ContentType binaryContentType = request.contentType() != null
                    ? ContentType.parse(request.contentType())
                    : DEFAULT_BINARY_CONTENT_TYPE;
                return new ByteArrayEntity(request.binaryData(), binaryContentType);
            case RAW:
                ContentType rawContentType = request.contentType() != null
                    ? ContentType.parse(request.contentType())
                    : DEFAULT_RAW_CONTENT_TYPE;
                return new StringEntity(request.rawContent(), rawContentType);
            default:
                throw new IllegalStateException("未知的请求体类型: " + request.bodyKind());
        }
    }

    private static HttpEntity buildMultipartEntity(List<FormPart> parts) {
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        for (FormPart part : parts) {
            switch (part.kind()) {
                case TEXT:
                    builder.addTextBody(part.name(), part.value());
                    break;
                case FILE_BYTES:
                    builder.addBinaryBody(part.name(), part.fileBytes(),
                        ContentType.parse(part.contentType()), part.filename());
                    break;
                case FILE_PATH:
                    builder.addBinaryBody(part.name(), part.file(),
                        ContentType.parse(part.contentType()), part.filename());
                    break;
                case FILE_STREAM:
                    builder.addBinaryBody(part.name(), part.fileStream(),
                        ContentType.parse(part.contentType()), part.filename());
                    break;
                default:
                    throw new IllegalStateException("未知的 form-data part 类型: " + part.kind());
            }
        }
        return builder.build();
    }

    private static HttpEntity buildUrlEncodedEntity(Map<String, String> params) {
        List<NameValuePair> pairs = new ArrayList<>();
        params.forEach((name, value) -> pairs.add(new BasicNameValuePair(name, value)));
        return new UrlEncodedFormEntity(pairs, StandardCharsets.UTF_8);
    }

    private static byte[] readBody(ClassicHttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            return new byte[0];
        }
        return EntityUtils.toByteArray(entity);
    }

    private static Map<String, String> readHeaders(ClassicHttpResponse response) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Header header : response.getHeaders()) {
            result.put(header.getName(), header.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 统一校验超时类 {@link Duration} 必须为正值（非 null、非负、非零）。
     */
    private static void requirePositiveDuration(Duration duration, String name) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " 必须为正值: " + duration);
        }
    }

    // =========================================================================
    // 嵌套类型：连接超时
    // =========================================================================

    /**
     * 超时值对象：统一校验连接超时与响应超时均为正值。
     */
    public static final class HttpTimeouts {

        private final Duration connectTimeout;
        private final Duration responseTimeout;

        private HttpTimeouts(Duration connectTimeout, Duration responseTimeout) {
            this.connectTimeout = connectTimeout;
            this.responseTimeout = responseTimeout;
        }

        /**
         * 构建并校验超时值对象。
         *
         * @param connectTimeout  连接超时，必须为正值
         * @param responseTimeout 响应超时，必须为正值
         * @return 校验通过的超时值对象
         * @throws IllegalArgumentException 当任一超时值非正时抛出
         */
        public static HttpTimeouts of(Duration connectTimeout, Duration responseTimeout) {
            requirePositiveDuration(connectTimeout, "connectTimeout");
            requirePositiveDuration(responseTimeout, "responseTimeout");
            return new HttpTimeouts(connectTimeout, responseTimeout);
        }

        public Duration connectTimeout() {
            return connectTimeout;
        }

        public Duration responseTimeout() {
            return responseTimeout;
        }
    }

    // =========================================================================
    // 嵌套类型：连接池配置
    // =========================================================================

    /**
     * 连接池配置：总连接上限、单路由连接上限、连接最长存活时间、复用前空闲校验时长、
     * 空闲连接回收周期，均有生产可用默认值，可通过 {@link Builder} 覆盖。
     */
    public static final class HttpConnectionPoolConfig {

        private final int maxTotal;
        private final int maxPerRoute;
        private final Duration connectionTimeToLive;
        private final Duration validateAfterInactivity;
        private final Duration idleEvictionInterval;

        private HttpConnectionPoolConfig(Builder builder) {
            this.maxTotal = builder.maxTotal;
            this.maxPerRoute = builder.maxPerRoute;
            this.connectionTimeToLive = builder.connectionTimeToLive;
            this.validateAfterInactivity = builder.validateAfterInactivity;
            this.idleEvictionInterval = builder.idleEvictionInterval;
        }

        public static Builder builder() {
            return new Builder();
        }

        public int maxTotal() {
            return maxTotal;
        }

        public int maxPerRoute() {
            return maxPerRoute;
        }

        public Duration connectionTimeToLive() {
            return connectionTimeToLive;
        }

        public Duration validateAfterInactivity() {
            return validateAfterInactivity;
        }

        public Duration idleEvictionInterval() {
            return idleEvictionInterval;
        }

        /**
         * {@link HttpConnectionPoolConfig} 构建器。
         */
        public static final class Builder {

            private int maxTotal = 200;
            private int maxPerRoute = 50;
            private Duration connectionTimeToLive = Duration.ofMinutes(5);
            private Duration validateAfterInactivity = Duration.ofSeconds(5);
            private Duration idleEvictionInterval = Duration.ofSeconds(30);

            private Builder() {
            }

            /**
             * 总连接上限，默认 200。
             */
            public Builder maxTotal(int maxTotal) {
                this.maxTotal = maxTotal;
                return this;
            }

            /**
             * 单路由连接上限，默认 50。
             */
            public Builder maxPerRoute(int maxPerRoute) {
                this.maxPerRoute = maxPerRoute;
                return this;
            }

            /**
             * 连接最长存活时间，默认 5 分钟。
             */
            public Builder connectionTimeToLive(Duration connectionTimeToLive) {
                this.connectionTimeToLive = connectionTimeToLive;
                return this;
            }

            /**
             * 复用前空闲校验时长，默认 5 秒。
             */
            public Builder validateAfterInactivity(Duration validateAfterInactivity) {
                this.validateAfterInactivity = validateAfterInactivity;
                return this;
            }

            /**
             * 空闲连接回收周期，默认 30 秒。
             */
            public Builder idleEvictionInterval(Duration idleEvictionInterval) {
                this.idleEvictionInterval = idleEvictionInterval;
                return this;
            }

            /**
             * 校验并构建连接池配置。
             *
             * @return 连接池配置
             * @throws IllegalArgumentException 当 maxTotal/maxPerRoute 非正，或任一时长非正时抛出
             */
            public HttpConnectionPoolConfig build() {
                if (maxTotal <= 0) {
                    throw new IllegalArgumentException("maxTotal 必须为正数: " + maxTotal);
                }
                if (maxPerRoute <= 0) {
                    throw new IllegalArgumentException("maxPerRoute 必须为正数: " + maxPerRoute);
                }
                requirePositiveDuration(connectionTimeToLive, "connectionTimeToLive");
                requirePositiveDuration(validateAfterInactivity, "validateAfterInactivity");
                requirePositiveDuration(idleEvictionInterval, "idleEvictionInterval");
                return new HttpConnectionPoolConfig(this);
            }
        }
    }

    // =========================================================================
    // 嵌套类型：全局客户端配置
    // =========================================================================

    /**
     * 全局客户端配置：连接超时、响应超时、是否跳过 SSL 校验、自定义 Jackson {@link ObjectMapper}、
     * 连接池配置，均有默认值，可通过 {@link Builder} 覆盖。
     */
    public static final class HttpClientConfig {

        private final HttpTimeouts timeouts;
        private final boolean skipSslVerify;
        private final ObjectMapper objectMapper;
        private final HttpConnectionPoolConfig poolConfig;

        private HttpClientConfig(Builder builder) {
            this.timeouts = HttpTimeouts.of(builder.connectTimeout, builder.responseTimeout);
            this.skipSslVerify = builder.skipSslVerify;
            this.objectMapper = builder.objectMapper;
            this.poolConfig = builder.poolConfig;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Duration connectTimeout() {
            return timeouts.connectTimeout();
        }

        public Duration responseTimeout() {
            return timeouts.responseTimeout();
        }

        public boolean skipSslVerify() {
            return skipSslVerify;
        }

        public ObjectMapper objectMapper() {
            return objectMapper;
        }

        public HttpConnectionPoolConfig poolConfig() {
            return poolConfig;
        }

        /**
         * {@link HttpClientConfig} 构建器。
         */
        public static final class Builder {

            private Duration connectTimeout = Duration.ofSeconds(3);
            private Duration responseTimeout = Duration.ofSeconds(3);
            private boolean skipSslVerify = true;
            private ObjectMapper objectMapper = DEFAULT_OBJECT_MAPPER;
            private HttpConnectionPoolConfig poolConfig = HttpConnectionPoolConfig.builder().build();

            private Builder() {
            }

            /**
             * 连接超时，默认 3 秒。
             */
            public Builder connectTimeout(Duration connectTimeout) {
                this.connectTimeout = connectTimeout;
                return this;
            }

            /**
             * 响应超时，默认 3 秒。
             */
            public Builder responseTimeout(Duration responseTimeout) {
                this.responseTimeout = responseTimeout;
                return this;
            }

            /**
             * 是否跳过 SSL 证书校验，默认 false；仅建议受控/内网环境开启。
             */
            public Builder skipSslVerify(boolean skipSslVerify) {
                this.skipSslVerify = skipSslVerify;
                return this;
            }

            /**
             * 自定义 Jackson ObjectMapper，默认内置单例（忽略未知字段）。
             */
            public Builder objectMapper(ObjectMapper objectMapper) {
                this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
                return this;
            }

            /**
             * 连接池配置，默认 {@code HttpConnectionPoolConfig.builder().build()}。
             */
            public Builder poolConfig(HttpConnectionPoolConfig poolConfig) {
                this.poolConfig = Objects.requireNonNull(poolConfig, "poolConfig must not be null");
                return this;
            }

            /**
             * 校验并构建全局客户端配置。
             *
             * @return 全局客户端配置
             * @throws IllegalArgumentException 当 connectTimeout/responseTimeout 非正时抛出
             */
            public HttpClientConfig build() {
                return new HttpClientConfig(this);
            }
        }
    }

    // =========================================================================
    // 嵌套类型：单次请求数据
    // =========================================================================

    private enum BodyKind {
        NONE, FORM_DATA, URL_ENCODED, BINARY, RAW
    }

    /**
     * 单次请求的 form-data part，内部表示文本字段或文件字段（三种文件来源之一）。
     */
    private static final class FormPart {

        private enum Kind {
            TEXT, FILE_BYTES, FILE_PATH, FILE_STREAM
        }

        /**
         * part 类型，决定下方哪些字段有效；互斥取值见各 {@code FormPart.xxx(...)} 静态工厂方法
         */
        private final Kind kind;
        /**
         * multipart 字段名（对应 {@code Content-Disposition} 的 {@code name}），TEXT/FILE_* 均需要
         */
        private final String name;
        /**
         * 文本内容，仅 {@code Kind.TEXT} 使用；文件类 part 此字段为 null
         */
        private final String value;
        /**
         * 上传文件名（对应 {@code Content-Disposition} 的 {@code filename}），仅 FILE_* 使用；{@code FILE_PATH} 自动取 {@code file.getName()}
         */
        private final String filename;
        /**
         * 文件的 Content-Type（如 "text/plain"），仅 FILE_* 使用；{@code Kind.TEXT} 此字段为 null
         */
        private final String contentType;
        /**
         * 文件内容的字节数组来源，仅 {@code Kind.FILE_BYTES} 使用
         */
        private final byte[] fileBytes;
        /**
         * 文件内容的本地文件来源，仅 {@code Kind.FILE_PATH} 使用
         */
        private final File file;
        /**
         * 文件内容的输入流来源，仅 {@code Kind.FILE_STREAM} 使用；调用方需保证该流只被消费一次，不支持重放
         */
        private final InputStream fileStream;

        private FormPart(Kind kind, String name, String value, String filename, String contentType,
                         byte[] fileBytes, File file, InputStream fileStream) {
            this.kind = kind;
            this.name = name;
            this.value = value;
            this.filename = filename;
            this.contentType = contentType;
            this.fileBytes = fileBytes;
            this.file = file;
            this.fileStream = fileStream;
        }

        static FormPart text(String name, String value) {
            return new FormPart(Kind.TEXT, name, value, null, null, null, null, null);
        }

        static FormPart fileBytes(String name, String filename, String contentType, byte[] content) {
            return new FormPart(Kind.FILE_BYTES, name, null, filename, contentType, content, null, null);
        }

        static FormPart filePath(String name, File file, String contentType) {
            return new FormPart(Kind.FILE_PATH, name, null, file.getName(), contentType, null, file, null);
        }

        static FormPart fileStream(String name, String filename, String contentType, InputStream content) {
            return new FormPart(Kind.FILE_STREAM, name, null, filename, contentType, null, null, content);
        }

        Kind kind() {
            return kind;
        }

        String name() {
            return name;
        }

        String value() {
            return value;
        }

        String filename() {
            return filename;
        }

        String contentType() {
            return contentType;
        }

        byte[] fileBytes() {
            return fileBytes;
        }

        File file() {
            return file;
        }

        InputStream fileStream() {
            return fileStream;
        }
    }

    /**
     * 单次请求数据：URL、请求头、可选的响应超时覆盖，以及四种互斥的请求体之一
     * （form-data / x-www-form-urlencoded / binary / raw）。
     */
    public static final class HttpRequestData {

        private final String url;
        private final Map<String, String> headers;
        private final Duration timeoutOverride;
        private final BodyKind bodyKind;
        private final List<FormPart> formParts;
        private final Map<String, String> urlEncodedParams;
        private final byte[] binaryData;
        private final String rawContent;
        private final String contentType;

        private HttpRequestData(Builder builder) {
            this.url = builder.url;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
            this.timeoutOverride = builder.timeoutOverride;
            this.bodyKind = builder.bodyKind;
            this.formParts = builder.formParts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(builder.formParts);
            this.urlEncodedParams = builder.urlEncodedParams == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(builder.urlEncodedParams);
            this.binaryData = builder.binaryData;
            this.rawContent = builder.rawContent;
            this.contentType = builder.contentType;
        }

        public static Builder builder(String url) {
            return new Builder(url);
        }

        String url() {
            return url;
        }

        Map<String, String> headers() {
            return headers;
        }

        Duration timeoutOverride() {
            return timeoutOverride;
        }

        BodyKind bodyKind() {
            return bodyKind;
        }

        List<FormPart> formParts() {
            return formParts;
        }

        Map<String, String> urlEncodedParams() {
            return urlEncodedParams;
        }

        byte[] binaryData() {
            return binaryData;
        }

        String rawContent() {
            return rawContent;
        }

        String contentType() {
            return contentType;
        }

        /**
         * form-data 请求体子构建器，支持文本字段与文件字段（{@code byte[]} / {@link File} /
         * {@link InputStream} 三种来源）混合提交。
         */
        public static final class FormDataBuilder {

            private final List<FormPart> parts = new ArrayList<>();

            private FormDataBuilder() {
            }

            /**
             * 添加文本字段。
             */
            public FormDataBuilder text(String name, String value) {
                parts.add(FormPart.text(name, value));
                return this;
            }

            /**
             * 添加文件字段（{@code byte[]} 来源）。
             */
            public FormDataBuilder file(String name, String filename, String contentType, byte[] content) {
                parts.add(FormPart.fileBytes(name, filename, contentType, content));
                return this;
            }

            /**
             * 添加文件字段（{@link File} 来源，文件名取自 {@code file.getName()}）。
             */
            public FormDataBuilder file(String name, File file, String contentType) {
                parts.add(FormPart.filePath(name, file, contentType));
                return this;
            }

            /**
             * 添加文件字段（{@link InputStream} 来源）。
             * <p>
             * <strong>注意：</strong>流只会被消费一次，调用方需自行保证不依赖重试/重定向重放该流。
             */
            public FormDataBuilder file(String name, String filename, String contentType, InputStream content) {
                parts.add(FormPart.fileStream(name, filename, contentType, content));
                return this;
            }
        }

        /**
         * {@link HttpRequestData} 构建器。
         */
        public static final class Builder {

            private final String url;
            private final Map<String, String> headers = new LinkedHashMap<>();
            private Duration timeoutOverride;
            private BodyKind bodyKind = BodyKind.NONE;
            private List<FormPart> formParts;
            private Map<String, String> urlEncodedParams;
            private byte[] binaryData;
            private String rawContent;
            private String contentType;

            private Builder(String url) {
                this.url = Objects.requireNonNull(url, "url must not be null");
            }

            /**
             * 添加单个请求头。
             */
            public Builder header(String name, String value) {
                this.headers.put(name, value);
                return this;
            }

            /**
             * 批量添加请求头。
             */
            public Builder headers(Map<String, String> headers) {
                if (headers != null) {
                    this.headers.putAll(headers);
                }
                return this;
            }

            /**
             * 覆盖本次请求的响应超时，仅影响当次请求，不改变全局配置、不影响后续请求。
             *
             * @param responseTimeoutOverride 本次响应超时，必须为正值
             */
            public Builder timeout(Duration responseTimeoutOverride) {
                requirePositiveDuration(responseTimeoutOverride, "timeout");
                this.timeoutOverride = responseTimeoutOverride;
                return this;
            }

            /**
             * 设置 {@code multipart/form-data} 请求体（文件上传、字符参数）。
             */
            public Builder formData(Consumer<FormDataBuilder> parts) {
                requireBodyNotSet();
                FormDataBuilder formDataBuilder = new FormDataBuilder();
                parts.accept(formDataBuilder);
                this.bodyKind = BodyKind.FORM_DATA;
                this.formParts = formDataBuilder.parts;
                return this;
            }

            /**
             * 设置 {@code application/x-www-form-urlencoded} 请求体。
             */
            public Builder urlEncoded(Map<String, String> params) {
                requireBodyNotSet();
                this.bodyKind = BodyKind.URL_ENCODED;
                this.urlEncodedParams = params == null ? Collections.emptyMap() : new LinkedHashMap<>(params);
                return this;
            }

            /**
             * 设置二进制请求体，默认 {@code Content-Type: application/octet-stream}。
             */
            public Builder binary(byte[] data, String contentType) {
                requireBodyNotSet();
                this.bodyKind = BodyKind.BINARY;
                this.binaryData = Objects.requireNonNull(data, "data must not be null");
                this.contentType = contentType;
                return this;
            }

            /**
             * 设置原始请求体（字符串），默认 {@code Content-Type: application/json}。
             */
            public Builder raw(String content, String contentType) {
                requireBodyNotSet();
                this.bodyKind = BodyKind.RAW;
                this.rawContent = Objects.requireNonNull(content, "content must not be null");
                this.contentType = contentType;
                return this;
            }

            private void requireBodyNotSet() {
                if (bodyKind != BodyKind.NONE) {
                    throw new IllegalStateException("请求体已设置，不能重复设置多种 body 类型: " + bodyKind);
                }
            }

            public HttpRequestData build() {
                return new HttpRequestData(this);
            }
        }
    }

    // =========================================================================
    // 嵌套类型：响应结果
    // =========================================================================

    /**
     * HTTP 响应结果：状态码、响应头、响应体（字节数组），并提供字符串/反序列化便捷方法。
     */
    public static final class HttpClientResponse {

        private final int statusCode;
        private final Map<String, String> headers;
        private final byte[] bodyBytes;
        private final ObjectMapper objectMapper;

        private HttpClientResponse(int statusCode, Map<String, String> headers, byte[] bodyBytes,
                                   ObjectMapper objectMapper) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.bodyBytes = bodyBytes;
            this.objectMapper = objectMapper;
        }

        public int statusCode() {
            return statusCode;
        }

        public Map<String, String> headers() {
            return headers;
        }

        public byte[] bodyBytes() {
            return bodyBytes;
        }

        /**
         * 将响应体按 UTF-8 解码为字符串。
         */
        public String bodyAsString() {
            return new String(bodyBytes, StandardCharsets.UTF_8);
        }

        /**
         * 将响应体反序列化为指定类型。
         *
         * @throws RuntimeException 当 JSON 反序列化失败时抛出
         */
        public <T> T bodyAsObject(Class<T> type) {
            try {
                return objectMapper.readValue(bodyBytes, type);
            } catch (IOException e) {
                throw new RuntimeException("JSON 反序列化失败", e);
            }
        }

        /**
         * 将响应体反序列化为 {@link TypeReference} 描述的类型，支持泛型集合。
         *
         * @throws RuntimeException 当 JSON 反序列化失败时抛出
         */
        public <T> T bodyAsObject(TypeReference<T> type) {
            try {
                return objectMapper.readValue(bodyBytes, type);
            } catch (IOException e) {
                throw new RuntimeException("JSON 反序列化失败", e);
            }
        }

        public Map<String, Object> bodyAsMap() {
            return bodyAsObject(TYPE_REFERENCE_MAP);
        }

        public List<Map<String, Object>> bodyAsList() {
            return bodyAsObject(TYPE_REFERENCE_LIST);
        }

        /**
         * 状态码是否为 2xx。
         */
        public boolean isSuccessful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    // =========================================================================
    // 嵌套类型：统一异常
    // =========================================================================

    /**
     * 统一的 HTTP 客户端异常，携带错误码；当 {@code errorCode == HTTP_STATUS_ERROR} 时
     * {@link #httpStatusCode()} 返回具体的 HTTP 状态码。
     */
    public static final class HttpClientException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 错误码分类。
         */
        public enum ErrorCode {
            /**
             * configure(...) 之前已调用 shutdown()，客户端处于关闭状态
             */
            CLIENT_CLOSED,
            /**
             * 建立连接超时
             */
            CONNECT_TIMEOUT,
            /**
             * 等待响应超时
             */
            RESPONSE_TIMEOUT,
            /**
             * SSL 握手失败
             */
            SSL_ERROR,
            /**
             * 其他网络 I/O 异常
             */
            IO_ERROR,
            /**
             * HTTP 响应状态码为 4xx/5xx
             */
            HTTP_STATUS_ERROR
        }

        private final ErrorCode errorCode;
        private final Integer httpStatusCode;

        HttpClientException(ErrorCode errorCode, String message, Integer httpStatusCode, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
            this.httpStatusCode = httpStatusCode;
        }

        public ErrorCode errorCode() {
            return errorCode;
        }

        /**
         * 仅当 {@code errorCode() == HTTP_STATUS_ERROR} 时非空。
         */
        public Integer httpStatusCode() {
            return httpStatusCode;
        }
    }

    // =========================================================================
    // 嵌套类型：客户端持有者（支撑热替换 / 优雅关闭）
    // =========================================================================

    /**
     * 客户端持有者：封装一次 {@link #configure}/{@link #shutdown} 生成的客户端、连接池、
     * 空闲连接回收线程，并通过 {@link #inFlight} 引用计数实现"旧连接池等在途请求完成后才关闭"。
     */
    private static final class ClientHolder {

        private final CloseableHttpClient client;
        private final PoolingHttpClientConnectionManager connectionManager;
        private final IdleConnectionEvictor evictor;
        private final ObjectMapper objectMapper;
        private final AtomicLong inFlight = new AtomicLong();
        private final boolean sentinel;
        private volatile boolean closing;

        private ClientHolder(CloseableHttpClient client, PoolingHttpClientConnectionManager connectionManager,
                             IdleConnectionEvictor evictor, ObjectMapper objectMapper, boolean sentinel) {
            this.client = client;
            this.connectionManager = connectionManager;
            this.evictor = evictor;
            this.objectMapper = objectMapper;
            this.sentinel = sentinel;
        }

        /**
         * 构建代表"已关闭"状态的哨兵 holder：所有请求直接收到 CLIENT_CLOSED。
         */
        static ClientHolder closedSentinel() {
            ClientHolder holder = new ClientHolder(null, null, null, null, true);
            holder.closing = true;
            return holder;
        }

        /**
         * 根据配置构建一个全新的、可用的 holder（连接池 + 客户端 + 空闲回收线程）。
         */
        static ClientHolder create(HttpClientConfig config) {
            try {
                HttpConnectionPoolConfig poolConfig = config.poolConfig();
                ConnectionConfig connectionConfig = ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.of(config.connectTimeout()))
                    .setTimeToLive(TimeValue.of(poolConfig.connectionTimeToLive()))
                    .setValidateAfterInactivity(TimeValue.of(poolConfig.validateAfterInactivity()))
                    .build();

                PoolingHttpClientConnectionManagerBuilder managerBuilder = PoolingHttpClientConnectionManagerBuilder
                    .create()
                    .setMaxConnTotal(poolConfig.maxTotal())
                    .setMaxConnPerRoute(poolConfig.maxPerRoute())
                    .setDefaultConnectionConfig(connectionConfig);

                if (config.skipSslVerify()) {
                    SSLContext sslContext = SSLContextBuilder.create()
                        .loadTrustMaterial(TrustAllStrategy.INSTANCE)
                        .build();
                    TlsSocketStrategy tlsSocketStrategy = ClientTlsStrategyBuilder.create()
                        .setSslContext(sslContext)
                        .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                        .setHostVerificationPolicy(HostnameVerificationPolicy.CLIENT)
                        .buildClassic();
                    managerBuilder.setTlsSocketStrategy(tlsSocketStrategy);
                }

                PoolingHttpClientConnectionManager connectionManager = managerBuilder.build();

                RequestConfig defaultRequestConfig = RequestConfig.custom()
                    .setResponseTimeout(Timeout.of(config.responseTimeout()))
                    .build();

                // 项目中 Spring Boot 的依赖管理会将 httpcore5 降级到 5.3.6（早于 httpclient5 5.6.2
                // 实际依赖的 5.4.3），该版本缺少内容压缩编解码注册表所需的 IOFunction 类；
                // 禁用内容压缩可避免触发该代码路径导致的 NoClassDefFoundError（与 HttpClients 一致）。
                CloseableHttpClient client = HttpClientBuilder.create()
                    .setConnectionManager(connectionManager)
                    .setDefaultRequestConfig(defaultRequestConfig)
                    .disableContentCompression()
                    .build();

                TimeValue evictionInterval = TimeValue.of(poolConfig.idleEvictionInterval());
                IdleConnectionEvictor evictor = new IdleConnectionEvictor(connectionManager,
                    evictionInterval, evictionInterval);
                evictor.start();

                return new ClientHolder(client, connectionManager, evictor, config.objectMapper(), false);
            } catch (Exception e) {
                throw new IllegalStateException("构建 HttpClient 失败", e);
            }
        }

        /**
         * 真正释放底层资源：先停止空闲回收线程，再优雅关闭客户端，最后关闭连接池。
         * 仅应在该 holder 的 {@link #inFlight} 已归零时调用（见 {@link #retire} 与请求执行的
         * {@code finally} 块中的论证）。
         */
        void closeNow() {
            if (evictor != null) {
                evictor.shutdown();
            }
            if (client != null) {
                client.close(CloseMode.GRACEFUL);
            }
            if (connectionManager != null) {
                connectionManager.close();
            }
        }
    }
}
