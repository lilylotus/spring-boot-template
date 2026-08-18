package com.example.template.util;

import com.example.template.config.HttpClientProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Builder;
import lombok.Getter;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一的 HTTP 客户端工具类（backend-common-utilities change design.md Decision 11），
 * 封装 GET/POST/PUT/PATCH、JSON/x-www-form-urlencoded/multipart/二进制请求体、可跳过 HTTPS
 * 证书校验、连接池化、可配置单次/全局超时的 HTTP 调用能力，供需要主动调用外部接口的模块
 * （如应用数据同步通知 {@code cn.nihility.rbac.sync.notify}）复用，避免各处重复处理连接池、
 * 超时、证书校验等细节。静态方法为主，风格对齐 {@link JacksonUtils}：内部持有一个进程级
 * 单例 {@code CloseableHttpClient}，不依赖 Spring 容器即可工作（默认配置见
 * {@link HttpClientProperties}），Spring 环境下由 {@link HttpClientProperties} 在
 * {@code @PostConstruct} 阶段调用 {@link #configure} 用真实配置覆盖默认值。
 */
public final class HttpClientUtils {

    /** 用于保护单例客户端懒加载/重建过程的锁对象。 */
    private static final Object LOCK = new Object();

    /** 当前生效的配置，未经 Spring 初始化时使用内置默认值。 */
    private static volatile HttpClientProperties properties = new HttpClientProperties();

    /** 进程级单例 HTTP 客户端，懒加载构建，{@link #configure} 后失效并在下次使用时重建。 */
    private static volatile CloseableHttpClient httpClient;

    /**
     * 工具类不允许实例化。
     */
    private HttpClientUtils() {
    }

    /**
     * 用给定配置覆盖当前生效配置，并使已构建的单例客户端失效（懒加载，下次实际调用时
     * 才按新配置重新构建，避免应用启动阶段就发生潜在的证书/网络初始化开销）。
     *
     * @param newProperties 新的配置
     */
    public static void configure(HttpClientProperties newProperties) {
        synchronized (LOCK) {
            properties = newProperties;
            httpClient = null;
        }
    }

    // ------------------------------------------------------------------
    // GET
    // ------------------------------------------------------------------

    /**
     * 发起一次 GET 请求。
     *
     * @param url                  请求地址
     * @param headers              请求头，允许为 {@code null}
     * @param queryParams          URL 查询参数，允许为 {@code null}
     * @param responseTimeoutMillis 本次请求的响应超时（毫秒），传 {@code null} 时使用全局默认值
     * @return HTTP 响应结果
     */
    public static HttpResult get(String url, Map<String, String> headers, Map<String, String> queryParams,
            Long responseTimeoutMillis) {
        HttpGet request = new HttpGet(buildUri(url, queryParams));
        applyHeaders(request, headers);
        applyResponseTimeout(request, responseTimeoutMillis);
        return execute(request);
    }

    // ------------------------------------------------------------------
    // POST
    // ------------------------------------------------------------------

    /**
     * 以 {@code application/json} 格式发起一次 POST 请求，请求体用 {@link JacksonUtils#toJson}
     * 序列化。
     *
     * @param url                  请求地址
     * @param headers              请求头，允许为 {@code null}
     * @param body                 待序列化为 JSON 的请求体对象
     * @param responseTimeoutMillis 本次请求的响应超时（毫秒），传 {@code null} 时使用全局默认值
     * @return HTTP 响应结果
     */
    public static HttpResult postJson(String url, Map<String, String> headers, Object body,
            Long responseTimeoutMillis) {
        HttpPost request = new HttpPost(url);
        applyHeaders(request, headers);
        applyResponseTimeout(request, responseTimeoutMillis);
        request.setEntity(new StringEntity(JacksonUtils.toJson(body), ContentType.APPLICATION_JSON));
        return execute(request);
    }

    /**
     * 以 {@code application/x-www-form-urlencoded} 格式发起一次 POST 请求。
     *
     * @param url                  请求地址
     * @param headers              请求头，允许为 {@code null}
     * @param formFields           表单字段，允许为 {@code null}
     * @param responseTimeoutMillis 本次请求的响应超时（毫秒），传 {@code null} 时使用全局默认值
     * @return HTTP 响应结果
     */
    public static HttpResult postForm(String url, Map<String, String> headers, Map<String, String> formFields,
            Long responseTimeoutMillis) {
        HttpPost request = new HttpPost(url);
        applyHeaders(request, headers);
        applyResponseTimeout(request, responseTimeoutMillis);
        request.setEntity(new UrlEncodedFormEntity(toNameValuePairs(formFields), StandardCharsets.UTF_8));
        return execute(request);
    }

    /**
     * 以 {@code multipart/form-data} 格式发起一次 POST 请求，同时支持文本字段与二进制文件字段。
     *
     * @param url                  请求地址
     * @param headers              请求头，允许为 {@code null}
     * @param fields               文本字段，允许为 {@code null}
     * @param files                二进制文件字段，允许为 {@code null}
     * @param responseTimeoutMillis 本次请求的响应超时（毫秒），传 {@code null} 时使用全局默认值
     * @return HTTP 响应结果
     */
    public static HttpResult postMultipart(String url, Map<String, String> headers, Map<String, String> fields,
            List<HttpFilePart> files, Long responseTimeoutMillis) {
        HttpPost request = new HttpPost(url);
        applyHeaders(request, headers);
        applyResponseTimeout(request, responseTimeoutMillis);
        request.setEntity(buildMultipartEntity(fields, files));
        return execute(request);
    }

    /**
     * 发起一次携带任意二进制请求体的 POST 请求。
     *
     * @param url                  请求地址
     * @param headers              请求头，允许为 {@code null}
     * @param content              请求体字节数组
     * @param contentType          请求体 {@code Content-Type}
     * @param responseTimeoutMillis 本次请求的响应超时（毫秒），传 {@code null} 时使用全局默认值
     * @return HTTP 响应结果
     */
    public static HttpResult postBinary(String url, Map<String, String> headers, byte[] content, String contentType,
            Long responseTimeoutMillis) {
        HttpPost request = new HttpPost(url);
        applyHeaders(request, headers);
        applyResponseTimeout(request, responseTimeoutMillis);
        request.setEntity(new ByteArrayEntity(content, ContentType.parse(contentType)));
        return execute(request);
    }

    // ------------------------------------------------------------------
    // PUT
    // ------------------------------------------------------------------

    /**
     * 以 {@code application/json} 格式发起一次 PUT 请求。
     *
     * @param url                  请求地址
     * @param headers              请求头，允许为 {@code null}
     * @param body                 待序列化为 JSON 的请求体对象
     * @param responseTimeoutMillis 本次请求的响应超时（毫秒），传 {@code null} 时使用全局默认值
     * @return HTTP 响应结果
     */
    public static HttpResult putJson(String url, Map<String, String> headers, Object body,
            Long responseTimeoutMillis) {
        HttpPut request = new HttpPut(url);
        applyHeaders(request, headers);
        applyResponseTimeout(request, responseTimeoutMillis);
        request.setEntity(new StringEntity(JacksonUtils.toJson(body), ContentType.APPLICATION_JSON));
        return execute(request);
    }

    /**
     * 以 {@code application/x-www-form-urlencoded} 格式发起一次 PUT 请求。
     *
     * @param url                  请求地址
     * @param headers              请求头，允许为 {@code null}
     * @param formFields           表单字段，允许为 {@code null}
     * @param responseTimeoutMillis 本次请求的响应超时（毫秒），传 {@code null} 时使用全局默认值
     * @return HTTP 响应结果
     */
    public static HttpResult putForm(String url, Map<String, String> headers, Map<String, String> formFields,
            Long responseTimeoutMillis) {
        HttpPut request = new HttpPut(url);
        applyHeaders(request, headers);
        applyResponseTimeout(request, responseTimeoutMillis);
        request.setEntity(new UrlEncodedFormEntity(toNameValuePairs(formFields), StandardCharsets.UTF_8));
        return execute(request);
    }

    /**
     * 发起一次携带任意二进制请求体的 PUT 请求。
     *
     * @param url                  请求地址
     * @param headers              请求头，允许为 {@code null}
     * @param content              请求体字节数组
     * @param contentType          请求体 {@code Content-Type}
     * @param responseTimeoutMillis 本次请求的响应超时（毫秒），传 {@code null} 时使用全局默认值
     * @return HTTP 响应结果
     */
    public static HttpResult putBinary(String url, Map<String, String> headers, byte[] content, String contentType,
            Long responseTimeoutMillis) {
        HttpPut request = new HttpPut(url);
        applyHeaders(request, headers);
        applyResponseTimeout(request, responseTimeoutMillis);
        request.setEntity(new ByteArrayEntity(content, ContentType.parse(contentType)));
        return execute(request);
    }

    // ------------------------------------------------------------------
    // PATCH
    // ------------------------------------------------------------------

    /**
     * 以 {@code application/json} 格式发起一次 PATCH 请求。
     *
     * @param url                  请求地址
     * @param headers              请求头，允许为 {@code null}
     * @param body                 待序列化为 JSON 的请求体对象
     * @param responseTimeoutMillis 本次请求的响应超时（毫秒），传 {@code null} 时使用全局默认值
     * @return HTTP 响应结果
     */
    public static HttpResult patchJson(String url, Map<String, String> headers, Object body,
            Long responseTimeoutMillis) {
        HttpPatch request = new HttpPatch(url);
        applyHeaders(request, headers);
        applyResponseTimeout(request, responseTimeoutMillis);
        request.setEntity(new StringEntity(JacksonUtils.toJson(body), ContentType.APPLICATION_JSON));
        return execute(request);
    }

    /**
     * 以 {@code application/x-www-form-urlencoded} 格式发起一次 PATCH 请求。
     *
     * @param url                  请求地址
     * @param headers              请求头，允许为 {@code null}
     * @param formFields           表单字段，允许为 {@code null}
     * @param responseTimeoutMillis 本次请求的响应超时（毫秒），传 {@code null} 时使用全局默认值
     * @return HTTP 响应结果
     */
    public static HttpResult patchForm(String url, Map<String, String> headers, Map<String, String> formFields,
            Long responseTimeoutMillis) {
        HttpPatch request = new HttpPatch(url);
        applyHeaders(request, headers);
        applyResponseTimeout(request, responseTimeoutMillis);
        request.setEntity(new UrlEncodedFormEntity(toNameValuePairs(formFields), StandardCharsets.UTF_8));
        return execute(request);
    }

    /**
     * 发起一次携带任意二进制请求体的 PATCH 请求。
     *
     * @param url                  请求地址
     * @param headers              请求头，允许为 {@code null}
     * @param content              请求体字节数组
     * @param contentType          请求体 {@code Content-Type}
     * @param responseTimeoutMillis 本次请求的响应超时（毫秒），传 {@code null} 时使用全局默认值
     * @return HTTP 响应结果
     */
    public static HttpResult patchBinary(String url, Map<String, String> headers, byte[] content, String contentType,
            Long responseTimeoutMillis) {
        HttpPatch request = new HttpPatch(url);
        applyHeaders(request, headers);
        applyResponseTimeout(request, responseTimeoutMillis);
        request.setEntity(new ByteArrayEntity(content, ContentType.parse(contentType)));
        return execute(request);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 执行请求并把响应转换为 {@link HttpResult}。
     *
     * @param request 待执行的请求
     * @return HTTP 响应结果
     */
    private static HttpResult execute(HttpUriRequestBase request) {
        try {
            return client().execute(request, response -> {
                byte[] body = response.getEntity() != null
                        ? EntityUtils.toByteArray(response.getEntity())
                        : new byte[0];
                Map<String, String> responseHeaders = new LinkedHashMap<>();
                for (Header header : response.getHeaders()) {
                    responseHeaders.put(header.getName(), header.getValue());
                }
                return HttpResult.builder()
                        .statusCode(response.getCode())
                        .headers(responseHeaders)
                        .body(body)
                        .build();
            });
        } catch (IOException e) {
            throw new IllegalStateException("HTTP 请求失败：" + request.getMethod() + " " + request.getRequestUri(), e);
        }
    }

    /**
     * 把请求头 Map 逐一添加到请求上。
     *
     * @param request 待添加请求头的请求
     * @param headers 请求头，允许为 {@code null}
     */
    private static void applyHeaders(HttpUriRequestBase request, Map<String, String> headers) {
        if (headers != null) {
            headers.forEach(request::addHeader);
        }
    }

    /**
     * 按调用方指定值或全局默认值设置本次请求的响应超时。
     *
     * @param request               待设置的请求
     * @param responseTimeoutMillis 调用方指定的响应超时（毫秒），为 {@code null} 时使用全局默认值
     */
    private static void applyResponseTimeout(HttpUriRequestBase request, Long responseTimeoutMillis) {
        long effective = responseTimeoutMillis != null ? responseTimeoutMillis : properties.getResponseTimeoutMillis();
        request.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(effective))
                .build());
    }

    /**
     * 按给定 URL 与查询参数构造请求 {@link URI}。
     *
     * @param url         请求地址
     * @param queryParams 查询参数，允许为 {@code null}
     * @return 拼接查询参数后的 {@link URI}
     */
    private static URI buildUri(String url, Map<String, String> queryParams) {
        try {
            URIBuilder builder = new URIBuilder(url);
            if (queryParams != null) {
                queryParams.forEach(builder::addParameter);
            }
            return builder.build();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("非法的请求地址：" + url, e);
        }
    }

    /**
     * 把表单字段 Map 转换为 {@link NameValuePair} 列表。
     *
     * @param fields 表单字段，允许为 {@code null}
     * @return {@link NameValuePair} 列表
     */
    private static List<NameValuePair> toNameValuePairs(Map<String, String> fields) {
        if (fields == null) {
            return List.of();
        }
        return fields.entrySet().stream()
                .map(entry -> (NameValuePair) new BasicNameValuePair(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 构造 {@code multipart/form-data} 请求体，同时携带文本字段与二进制文件字段。
     *
     * @param fields 文本字段，允许为 {@code null}
     * @param files  二进制文件字段，允许为 {@code null}
     * @return 构造好的 multipart 请求体
     */
    private static org.apache.hc.core5.http.HttpEntity buildMultipartEntity(Map<String, String> fields,
            List<HttpFilePart> files) {
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        if (fields != null) {
            fields.forEach(builder::addTextBody);
        }
        if (files != null) {
            for (HttpFilePart file : files) {
                ContentType contentType = file.contentType() != null
                        ? ContentType.parse(file.contentType())
                        : ContentType.APPLICATION_OCTET_STREAM;
                builder.addBinaryBody(file.fieldName(), file.content(), contentType, file.fileName());
            }
        }
        return builder.build();
    }

    /**
     * 获取进程级单例 HTTP 客户端，懒加载并按需重建（双重检查锁定）。
     *
     * @return 单例 HTTP 客户端
     */
    private static CloseableHttpClient client() {
        CloseableHttpClient local = httpClient;
        if (local == null) {
            synchronized (LOCK) {
                local = httpClient;
                if (local == null) {
                    local = buildClient(properties);
                    httpClient = local;
                }
            }
        }
        return local;
    }

    /**
     * 按给定配置构建一个新的连接池化 HTTP 客户端。
     *
     * @param props 生效配置
     * @return 新构建的 HTTP 客户端
     */
    private static CloseableHttpClient buildClient(HttpClientProperties props) {
        PoolingHttpClientConnectionManagerBuilder connectionManagerBuilder = PoolingHttpClientConnectionManagerBuilder
                .create()
                .setMaxConnTotal(props.getMaxTotal())
                .setMaxConnPerRoute(props.getMaxPerRoute())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(props.getConnectTimeoutMillis()))
                        .build());
        if (props.isSkipSslVerify()) {
            connectionManagerBuilder.setTlsSocketStrategy(buildTrustAllTlsStrategy());
        }
        HttpClientConnectionManager connectionManager = connectionManagerBuilder.build();

        RequestConfig defaultRequestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(props.getResponseTimeoutMillis()))
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(defaultRequestConfig)
                .build();
    }

    /**
     * 构造一个信任所有证书、不校验主机名的 TLS 连接策略，仅供 {@code skipSslVerify=true}
     * 时使用（内网自签名证书场景）。
     *
     * @return 信任所有证书的 TLS 连接策略
     */
    private static TlsSocketStrategy buildTrustAllTlsStrategy() {
        try {
            SSLContext sslContext = SSLContextBuilder.create()
                    .loadTrustMaterial((chain, authType) -> true)
                    .build();
            return ClientTlsStrategyBuilder.create()
                    .setSslContext(sslContext)
                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .buildClassic();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("构建跳过证书校验的 SSLContext 失败", e);
        }
    }

    /**
     * 统一的 HTTP 响应结果载体：状态码、响应头、响应体字节数组，同时提供 {@code bodyAsObj}
     * 系列方法把响应体按 JSON 直接反序列化为调用方指定的对象类型（内部委托
     * {@link JacksonUtils}），调用方无需再额外手动调用一次 {@link JacksonUtils} 转换。
     */
    @Getter
    @Builder
    public static class HttpResult {

        /** HTTP 状态码。 */
        private final int statusCode;

        /** 响应头。 */
        private final Map<String, String> headers;

        /** 响应体原始字节数组。 */
        private final byte[] body;

        /**
         * 把响应体字节数组按 UTF-8 解码为字符串。
         *
         * @return 响应体字符串，响应体为空时返回空字符串
         */
        public String bodyAsString() {
            return body != null ? new String(body, StandardCharsets.UTF_8) : "";
        }

        /**
         * 把响应体按 JSON 反序列化为指定类型的对象。
         *
         * @param clazz 目标类型
         * @param <T>   目标类型
         * @return 反序列化后的对象
         */
        public <T> T bodyAsObj(Class<T> clazz) {
            return JacksonUtils.toObj(body, clazz);
        }

        /**
         * 把响应体按 JSON 反序列化为指定的（可带泛型的）类型，用于反序列化目标本身带泛型参数
         * 的场景（如 {@code List<XxxDto>}）。
         *
         * @param typeReference 目标类型描述
         * @param <T>           目标类型
         * @return 反序列化后的对象
         */
        public <T> T bodyAsObj(TypeReference<T> typeReference) {
            return JacksonUtils.toObj(body, typeReference);
        }

        /**
         * 把响应体按 JSON 反序列化为指定类型的对象。
         *
         * @param type 目标类型
         * @param <T>  目标类型
         * @return 反序列化后的对象
         */
        public <T> T bodyAsObj(Type type) {
            return JacksonUtils.toObj(body, type);
        }
    }

    /**
     * {@code multipart/form-data} 请求中的一个二进制文件字段。
     *
     * @param fieldName   表单字段名
     * @param fileName    文件名
     * @param content     文件内容字节数组
     * @param contentType 文件内容类型，允许为 {@code null}（回退为 {@code application/octet-stream}）
     */
    public record HttpFilePart(String fieldName, String fileName, byte[] content, String contentType) {
    }
}
