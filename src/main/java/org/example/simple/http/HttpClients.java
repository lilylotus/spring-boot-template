package org.example.simple.http;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.FileEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 支持常用请求方法、请求体编码和 JSON 响应转换的静态 HTTP 工具类。
 * <p>
 * 全部静态请求共享线程安全的连接池。首次请求使用安全默认配置，应用可通过
 * {@link #configure(HttpClientConfig)} 设置全局配置，并在停止时调用 {@link #shutdown()}。
 * 调用示例：
 * <pre>{@code
 * HttpResponseData response = HttpClients.get("http://127.0.0.1:23456/hello");
 * }</pre>
 */
public final class HttpClients {

    private static final ReentrantReadWriteLock CLIENT_LOCK = new ReentrantReadWriteLock(true);
    private static final Lock READ_LOCK = CLIENT_LOCK.readLock();
    private static final Lock WRITE_LOCK = CLIENT_LOCK.writeLock();

    private static volatile ClientRuntime runtime;
    private static volatile boolean shutdown;

    private HttpClients() {
        throw new UnsupportedOperationException("HTTP 工具类不能实例化");
    }

    /**
     * 原子替换静态全局客户端配置。
     * <p>
     * 如果已有请求正在执行，本方法会等待请求完成后再关闭旧连接池。启用跳过 SSL 校验会使连接可能遭受
     * 中间人攻击，仅应用于受控测试或可信内网环境。
     *
     * @param config 新的静态全局配置
     * @throws NullPointerException 配置为空时抛出
     * @throws HttpClientException 新客户端创建或旧客户端关闭失败时抛出
     */
    public static void configure(HttpClientConfig config) {
        Objects.requireNonNull(config, "HTTP 客户端配置不能为空");
        WRITE_LOCK.lock();
        try {
            ClientRuntime replacement = new ClientRuntime(config);
            ClientRuntime previous = runtime;
            runtime = replacement;
            shutdown = false;
            if (previous != null) {
                previous.close();
            }
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * 发送请求并返回完整缓冲的原始响应。
     *
     * @param request 请求描述
     * @return 原始响应
     * @throws HttpClientException 客户端关闭、请求失败、超时或状态非 {@code 2xx} 时抛出
     */
    public static HttpResponseData execute(HttpRequestData request) {
        Objects.requireNonNull(request, "HTTP 请求不能为空");
        return withRuntime(current -> current.execute(request));
    }

    /**
     * 发送请求并将 JSON 响应转换为普通对象。
     *
     * @param request 请求描述
     * @param responseType 响应目标类型
     * @param <T> 响应对象类型
     * @return 转换后的响应对象
     * @throws HttpClientException 请求或 JSON 转换失败时抛出
     */
    public static <T> T executeJson(HttpRequestData request, Class<T> responseType) {
        Objects.requireNonNull(request, "HTTP 请求不能为空");
        Objects.requireNonNull(responseType, "JSON 响应目标类型不能为空");
        return withRuntime(current -> current.executeJson(request, responseType));
    }

    /**
     * 发送请求并将 JSON 响应转换为保留泛型信息的对象。
     *
     * @param request 请求描述
     * @param responseType 响应目标类型引用
     * @param <T> 响应对象类型
     * @return 转换后的响应对象
     * @throws HttpClientException 请求或 JSON 转换失败时抛出
     */
    public static <T> T executeJson(HttpRequestData request, TypeReference<T> responseType) {
        Objects.requireNonNull(request, "HTTP 请求不能为空");
        Objects.requireNonNull(responseType, "JSON 响应目标类型不能为空");
        return withRuntime(current -> current.executeJson(request, responseType));
    }

    /**
     * 静态发送不带请求体的 GET 请求。
     *
     * @param url HTTP 或 HTTPS URL 文本，例如 {@code http://127.0.0.1:23456/hello}
     * @return 原始响应
     */
    public static HttpResponseData get(String url) {
        return execute(HttpRequestData.get(url).build());
    }

    /**
     * 静态发送带请求体的 POST 请求。
     *
     * @param url HTTP 或 HTTPS URL 文本，例如 {@code http://127.0.0.1:23456/hello}
     * @param body 请求体
     * @return 原始响应
     */
    public static HttpResponseData post(String url, HttpRequestBody body) {
        return execute(HttpRequestData.post(url).body(body).build());
    }

    /**
     * 静态发送带请求体的 PUT 请求。
     *
     * @param url HTTP 或 HTTPS URL 文本，例如 {@code http://127.0.0.1:23456/hello}
     * @param body 请求体
     * @return 原始响应
     */
    public static HttpResponseData put(String url, HttpRequestBody body) {
        return execute(HttpRequestData.put(url).body(body).build());
    }

    /**
     * 静态发送不带请求体的 DELETE 请求。
     *
     * @param url HTTP 或 HTTPS URL 文本，例如 {@code http://127.0.0.1:23456/hello}
     * @return 原始响应
     */
    public static HttpResponseData delete(String url) {
        return execute(HttpRequestData.delete(url).build());
    }

    /**
     * 静态发送带请求体的 DELETE 请求。
     *
     * @param url HTTP 或 HTTPS URL 文本，例如 {@code http://127.0.0.1:23456/hello}
     * @param body 请求体
     * @return 原始响应
     */
    public static HttpResponseData delete(String url, HttpRequestBody body) {
        return execute(HttpRequestData.delete(url).body(body).build());
    }

    /**
     * 幂等关闭静态共享连接池。
     * <p>
     * 本方法等待正在执行的请求完成。关闭后静态请求会失败，直到调用
     * {@link #configure(HttpClientConfig)} 重新配置客户端。
     *
     * @throws HttpClientException 关闭共享连接池失败时抛出
     */
    public static void shutdown() {
        WRITE_LOCK.lock();
        try {
            ClientRuntime previous = runtime;
            runtime = null;
            shutdown = true;
            if (previous != null) {
                previous.close();
            }
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static <T> T withRuntime(Function<ClientRuntime, T> action) {
        initializeDefaultRuntimeIfNeeded();
        READ_LOCK.lock();
        try {
            ClientRuntime current = runtime;
            if (current == null) {
                throw new HttpClientException(HttpErrorType.CLIENT_CLOSED, "HTTP 静态客户端已关闭");
            }
            return action.apply(current);
        } finally {
            READ_LOCK.unlock();
        }
    }

    private static void initializeDefaultRuntimeIfNeeded() {
        if (runtime != null || shutdown) {
            return;
        }
        WRITE_LOCK.lock();
        try {
            if (runtime == null && !shutdown) {
                runtime = new ClientRuntime(HttpClientConfig.defaults());
            }
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static CloseableHttpClient createApacheClient(HttpClientConfig config) {
        HttpConnectionPoolConfig poolConfig = config.connectionPoolConfig();
        HttpClientBuilder clientBuilder = org.apache.hc.client5.http.impl.classic.HttpClients.custom()
            .setConnectionManager(createConnectionManager(config))
            .setDefaultRequestConfig(createRequestConfig(config.connectTimeout(), config.responseTimeout()))
            .evictExpiredConnections()
            .evictIdleConnections(toTimeValue(poolConfig.idleEvictionTimeout(), "空闲连接回收时长"))
            .disableAutomaticRetries();
        return clientBuilder.build();
    }

    static PoolingHttpClientConnectionManager createConnectionManager(HttpClientConfig config) {
        HttpConnectionPoolConfig poolConfig = config.connectionPoolConfig();
        PoolingHttpClientConnectionManagerBuilder managerBuilder =
            PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(poolConfig.maxTotalConnections())
                .setMaxConnPerRoute(poolConfig.maxConnectionsPerRoute())
                .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT)
                .setConnPoolPolicy(PoolReusePolicy.LIFO)
                .setOffLockDisposalEnabled(true);
        if (config.skipSslVerification()) {
            managerBuilder.setTlsSocketStrategy(createInsecureTlsStrategy());
        }
        managerBuilder.setDefaultConnectionConfig(ConnectionConfig.custom()
            .setConnectTimeout(toTimeout(config.connectTimeout(), "连接超时"))
            .setSocketTimeout(toTimeout(config.responseTimeout(), "响应超时"))
            .setTimeToLive(toTimeValue(poolConfig.connectionTimeToLive(), "连接最长存活时间"))
            .setValidateAfterInactivity(
                toTimeValue(poolConfig.validateAfterInactivity(), "连接空闲校验时长"))
            .build());
        return managerBuilder.build();
    }

    private static org.apache.hc.client5.http.ssl.TlsSocketStrategy createInsecureTlsStrategy() {
        try {
            SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial((certificateChain, authenticationType) -> true)
                .build();
            return ClientTlsStrategyBuilder.create()
                .setSslContext(sslContext)
                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .buildClassic();
        } catch (Exception exception) {
            throw new HttpClientException(HttpErrorType.INVALID_ARGUMENT, "创建跳过 SSL 校验的客户端失败", exception);
        }
    }

    static RequestConfig createRequestConfig(Duration connectTimeout, Duration responseTimeout) {
        return RequestConfig.custom()
            .setConnectionRequestTimeout(toTimeout(connectTimeout, "连接超时"))
            .setResponseTimeout(toTimeout(responseTimeout, "响应超时"))
            .build();
    }

    private static Timeout toTimeout(Duration duration, String name) {
        return Timeout.ofMilliseconds(HttpTimeouts.requireValid(duration, name).toMillis());
    }

    private static TimeValue toTimeValue(Duration duration, String name) {
        return TimeValue.ofMilliseconds(HttpTimeouts.requireValid(duration, name).toMillis());
    }

    private static URI appendQueryParameters(String url, Map<String, List<String>> parameters) {
        try {
            URIBuilder builder = new URIBuilder(url, StandardCharsets.UTF_8);
            parameters.forEach((name, values) -> values.forEach(value -> builder.addParameter(name, value)));
            return builder.build();
        } catch (Exception exception) {
            throw new HttpClientException(HttpErrorType.INVALID_ARGUMENT, "构建请求 URL 失败", exception);
        }
    }

    private static List<NameValuePair> toNameValuePairs(Map<String, List<String>> values) {
        List<NameValuePair> pairs = new ArrayList<>();
        values.forEach((name, fieldValues) ->
            fieldValues.forEach(value -> pairs.add(new BasicNameValuePair(name, value))));
        return pairs;
    }

    private static Map<String, List<String>> collectHeaders(Header[] headers) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (Header header : headers) {
            values.computeIfAbsent(header.getName(), ignored -> new ArrayList<>()).add(header.getValue());
        }
        return values;
    }

    private static void requireReadable(Path path) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new HttpClientException(
                HttpErrorType.INVALID_ARGUMENT,
                "请求文件在执行时不存在或不可读: " + path);
        }
    }

    private static HttpClientException transportFailure(IOException exception) {
        if (isTimeout(exception)) {
            return new HttpClientException(HttpErrorType.TIMEOUT, "HTTP 请求超时", exception);
        }
        return new HttpClientException(HttpErrorType.TRANSPORT, "HTTP 请求传输失败", exception);
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                || current instanceof ConnectTimeoutException
                || current instanceof ConnectionRequestTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 与一份不可变全局配置绑定的共享客户端运行时。
     */
    private static final class ClientRuntime {

        private final HttpClientConfig config;
        private final ObjectMapper objectMapper;
        private final CloseableHttpClient httpClient;

        private ClientRuntime(HttpClientConfig config) {
            this.config = config;
            objectMapper = config.objectMapper();
            httpClient = createApacheClient(config);
        }

        private HttpResponseData execute(HttpRequestData request) {
            HttpUriRequestBase apacheRequest = createApacheRequest(request);
            try {
                return httpClient.execute(apacheRequest, response -> {
                    byte[] body = response.getEntity() == null
                        ? new byte[0]
                        : EntityUtils.toByteArray(response.getEntity());
                    HttpResponseData responseData = new HttpResponseData(
                        response.getCode(),
                        collectHeaders(response.getHeaders()),
                        body);
                    if (response.getCode() < 200 || response.getCode() >= 300) {
                        throw new HttpClientException(
                            HttpErrorType.HTTP_STATUS,
                            "HTTP 服务返回非成功状态: " + response.getCode(),
                            responseData);
                    }
                    return responseData;
                });
            } catch (HttpClientException exception) {
                throw exception;
            } catch (IOException exception) {
                throw transportFailure(exception);
            }
        }

        private <T> T executeJson(HttpRequestData request, Class<T> responseType) {
            HttpResponseData response = execute(request);
            try {
                return objectMapper.readValue(response.body(), responseType);
            } catch (RuntimeException exception) {
                throw new HttpClientException(HttpErrorType.JSON_CONVERSION, "JSON 响应转换失败", exception);
            }
        }

        private <T> T executeJson(HttpRequestData request, TypeReference<T> responseType) {
            HttpResponseData response = execute(request);
            try {
                return objectMapper.readValue(response.body(), responseType);
            } catch (RuntimeException exception) {
                throw new HttpClientException(HttpErrorType.JSON_CONVERSION, "JSON 响应转换失败", exception);
            }
        }

        private HttpUriRequestBase createApacheRequest(HttpRequestData request) {
            URI requestUri = appendQueryParameters(request.url(), request.queryParameters());
            HttpUriRequestBase apacheRequest = new HttpUriRequestBase(request.method().name(), requestUri);
            request.headers().forEach((name, values) ->
                values.forEach(value -> apacheRequest.addHeader(name, value)));
            request.body().ifPresent(body -> apacheRequest.setEntity(createEntity(body)));
            Duration responseTimeout = request.timeout().orElse(config.responseTimeout());
            apacheRequest.setConfig(createRequestConfig(config.connectTimeout(), responseTimeout));
            return apacheRequest;
        }

        private HttpEntity createEntity(HttpRequestBody body) {
            if (body instanceof JsonRequestBody(Object value)) {
                try {
                    return new ByteArrayEntity(
                        objectMapper.writeValueAsBytes(value),
                        ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8));
                } catch (RuntimeException exception) {
                    throw new HttpClientException(HttpErrorType.JSON_CONVERSION, "JSON 请求序列化失败", exception);
                }
            }
            if (body instanceof FormRequestBody(Map<String, List<String>> fields)) {
                return new UrlEncodedFormEntity(toNameValuePairs(fields), StandardCharsets.UTF_8);
            }
            if (body instanceof MultipartRequestBody multipartBody) {
                return createMultipartEntity(multipartBody);
            }
            if (body instanceof BinaryRequestBody(Path path, String contentType)) {
                requireReadable(path);
                return new FileEntity(path.toFile(), ContentType.parse(contentType));
            }
            throw new HttpClientException(HttpErrorType.INVALID_ARGUMENT, "不支持的 HTTP 请求体类型");
        }

        private HttpEntity createMultipartEntity(MultipartRequestBody body) {
            MultipartEntityBuilder builder = MultipartEntityBuilder.create().setCharset(StandardCharsets.UTF_8);
            ContentType textContentType = ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8);
            body.textFields().forEach((name, values) ->
                values.forEach(value -> builder.addTextBody(name, value, textContentType)));
            body.fileParts().forEach(part -> {
                requireReadable(part.path());
                builder.addBinaryBody(
                    part.fieldName(),
                    part.path(),
                    ContentType.parse(part.contentType()),
                    part.fileName());
            });
            return builder.build();
        }

        private void close() {
            try {
                httpClient.close();
            } catch (IOException exception) {
                throw new HttpClientException(HttpErrorType.TRANSPORT, "关闭 HTTP 静态客户端失败", exception);
            }
        }
    }
}
