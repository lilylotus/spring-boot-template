package com.example.template.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 简单 httpclient 工具类
 *
 * @author yuanzx
 */
public class SimpleHttpClientUtils {

    private SimpleHttpClientUtils() {
    }

    private static final Logger log = LoggerFactory.getLogger(SimpleHttpClientUtils.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DATE_TIME = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE = "yyyy-MM-dd";
    private static final String TIME = "HH:mm:ss";

    /**
     * Default value for disabling SSL validation.
     */
    public static final boolean DEFAULT_DISABLE_SSL_VALIDATION = true;

    /**
     * Default value for max number od connections.
     */
    public static final int DEFAULT_MAX_CONNECTIONS = 200;

    /**
     * Default value for max number od connections per route.
     */
    public static final int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = 50;

    /**
     * Default value for time to live.
     */
    public static final long DEFAULT_TIME_TO_LIVE = 900L;

    /**
     * Default time to live unit.
     */
    public static final TimeUnit DEFAULT_TIME_TO_LIVE_UNIT = TimeUnit.SECONDS;

    /**
     * Default value for following redirects.
     */
    public static final boolean DEFAULT_FOLLOW_REDIRECTS = false;

    /**
     * Default value for connection timeout.
     */
    public static final int DEFAULT_CONNECTION_TIMEOUT = 3000;

    /**
     * Default value for connection timer repeat.
     */
    public static final int DEFAULT_CONNECTION_TIMER_REPEAT = 3000;

    private static volatile HttpClientConnectionManager connectionManager;

    private static volatile CloseableHttpClient httpClient;

    static {
        OBJECT_MAPPER.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_TIME);
        JavaTimeModule timeModule = new JavaTimeModule();

        timeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DATE_TIME)));
        timeModule.addSerializer(LocalDate.class, new LocalDateSerializer(DateTimeFormatter.ofPattern(DATE)));
        timeModule.addSerializer(LocalTime.class, new LocalTimeSerializer(DateTimeFormatter.ofPattern(TIME)));
        timeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DATE_TIME)));
        timeModule.addDeserializer(LocalDate.class, new LocalDateDeserializer(DateTimeFormatter.ofPattern(DATE)));
        timeModule.addDeserializer(LocalTime.class, new LocalTimeDeserializer(DateTimeFormatter.ofPattern(TIME)));
        OBJECT_MAPPER.setDateFormat(dateFormat);
        OBJECT_MAPPER.registerModule(timeModule);
    }

    private static CloseableHttpClient defaultHttpClient() {
        if (null == httpClient) {
            synchronized (SimpleHttpClientUtils.class) {
                if (null == httpClient) {
                    connectionManager = newConnectionManager(true, DEFAULT_MAX_CONNECTIONS,
                            DEFAULT_MAX_CONNECTIONS_PER_ROUTE, DEFAULT_TIME_TO_LIVE, DEFAULT_TIME_TO_LIVE_UNIT);

                    RequestConfig defaultRequestConfig = RequestConfig.custom()
                            .setConnectTimeout(DEFAULT_CONNECTION_TIMEOUT)
                            .setRedirectsEnabled(DEFAULT_FOLLOW_REDIRECTS)
                            .build();

                    httpClient = HttpClientBuilder.create()
                            .disableContentCompression()
                            .disableCookieManagement()
                            // 定期清理闲置连接
                            .evictExpiredConnections()
                            .evictIdleConnections(120L, TimeUnit.SECONDS)
                            .useSystemProperties()
                            .setConnectionManager(connectionManager)
                            .setDefaultRequestConfig(defaultRequestConfig)
                            .build();

                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (null != httpClient) {
                            try {
                                httpClient.close();
                            } catch (IOException e) {
                                log.error("HttpSimpleClientUtils#httpClient close error", e);
                            }
                        }
                        if (null != connectionManager) {
                            connectionManager.shutdown();
                        }
                    }, "HttpSimpleClientUtils#ShutdownHook"));
                }
            }
        }
        return httpClient;
    }

    public static HttpClientConnectionManager newConnectionManager(boolean disableSslValidation,
                                                                   int maxTotalConnections, int maxConnectionsPerRoute) {
        return newConnectionManager(disableSslValidation, maxTotalConnections,
                maxConnectionsPerRoute, -1, TimeUnit.MILLISECONDS);
    }

    public static HttpClientConnectionManager newConnectionManager(boolean disableSslValidation, int maxTotalConnections,
                                                                   int maxConnectionsPerRoute, long timeToLive, TimeUnit timeUnit) {
        RegistryBuilder<ConnectionSocketFactory> registryBuilder = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("HTTP", PlainConnectionSocketFactory.INSTANCE);
        try {
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, new TrustManager[]{new DisabledValidationTrustManager()}, new SecureRandom());
            registryBuilder.register("HTTPS", new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE));
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log.warn("Error creating SSLContext", e);
        }

        final Registry<ConnectionSocketFactory> registry = registryBuilder.build();
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(
                registry, null, null, null, timeToLive, timeUnit);
        connectionManager.setMaxTotal(maxTotalConnections);
        connectionManager.setDefaultMaxPerRoute(maxConnectionsPerRoute);

        return connectionManager;
    }

    /**
     * Creates a new Options Instance.
     *
     * @param connectTimeout     value.
     * @param connectTimeoutUnit with the TimeUnit for the timeout value.
     * @param readTimeout        value.
     * @param readTimeoutUnit    with the TimeUnit for the timeout value.
     * @param followRedirects    if the request should follow 3xx redirections.
     */
    public static RequestConfig requestConfigOption(long connectTimeout, TimeUnit connectTimeoutUnit,
                                                    long readTimeout, TimeUnit readTimeoutUnit,
                                                    boolean followRedirects) {
        return RequestConfig.custom()
                .setConnectTimeout((int) connectTimeoutUnit.toMillis(connectTimeout))
                .setSocketTimeout((int) readTimeoutUnit.toMillis(readTimeout))
                .setRedirectsEnabled(followRedirects)
                .build();
    }

    public static <R> R execute(HttpUriRequest request, Function<CloseableHttpResponse, R> func) {
        CloseableHttpClient client = defaultHttpClient();
        try (CloseableHttpResponse response = client.execute(request)) {
            R result = func.apply(response);
            EntityUtils.consume(response.getEntity());
            return result;
        } catch (ClientProtocolException e) {
            String error = String.format("请求 [%s: %s] 协议异常", request.getMethod(), request.getURI());
            log.error(error, e);
            throw new IllegalArgumentException(error, e);
        } catch (IOException e) {
            String error = String.format("请求 [%s: %s] IO异常", request.getMethod(), request.getURI());
            log.error(error, e);
            throw new IllegalArgumentException(error, e);
        } catch (Exception ex) {
            String error = String.format("请求 [%s: %s] 其它异常", request.getMethod(), request.getURI());
            log.error(error, ex);
            throw new IllegalArgumentException(error, ex);
        }
    }

    public static CloseableHttpResponse executeWithResponse(HttpUriRequest request) {
        CloseableHttpClient client = defaultHttpClient();
        try {
            return client.execute(request);
        } catch (ClientProtocolException e) {
            String error = String.format("请求 [%s: %s] 协议异常", request.getMethod(), request.getURI());
            log.error(error, e);
            throw new IllegalArgumentException(error, e);
        } catch (IOException e) {
            String error = String.format("请求 [%s: %s] IO异常", request.getMethod(), request.getURI());
            log.error(error, e);
            throw new IllegalArgumentException(error, e);
        } catch (Exception ex) {
            String error = String.format("请求 [%s: %s] 其它异常", request.getMethod(), request.getURI());
            log.error(error, ex);
            throw new IllegalArgumentException(error, ex);
        }
    }

    public static HttpPost createJsonPost(String url, Map<String, String> params) {
        return createJsonPost(url, toJson(params));
    }

    public static HttpPost createJsonPost(String url, String body) {
        HttpPost post = new HttpPost(url);
        StringEntity bodyEntity = new StringEntity(body, StandardCharsets.UTF_8);
        bodyEntity.setContentType(ContentType.APPLICATION_JSON.getMimeType());
        bodyEntity.setContentEncoding("UTF-8");
        post.setEntity(bodyEntity);
        post.setHeader("Content-Type", ContentType.APPLICATION_JSON.getMimeType());
        return post;
    }

    public static HttpPost createFormUrlencoded(String url, Map<String, String> formData) {
        HttpPost post = new HttpPost(url);
        // 创建 NameValuePair 列表
        List<NameValuePair> paramsList = new ArrayList<>();
        for (Map.Entry<String, String> entry : formData.entrySet()) {
            paramsList.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }
        try {
            UrlEncodedFormEntity urlEncodedFormEntity = new UrlEncodedFormEntity(paramsList);
            post.setEntity(urlEncodedFormEntity);
        } catch (UnsupportedEncodingException e) {
            // ignore
        }
        post.setHeader("Content-Type", ContentType.APPLICATION_FORM_URLENCODED.getMimeType());
        return post;
    }

    public static <R> R executeJsonPost(String url, String body, Class<R> ret) {
        HttpPost post = new HttpPost(url);
        StringEntity bodyEntity = new StringEntity(body, StandardCharsets.UTF_8);
        bodyEntity.setContentType(ContentType.APPLICATION_JSON.getMimeType());
        bodyEntity.setContentEncoding("UTF-8");
        post.setEntity(bodyEntity);
        Function<CloseableHttpResponse, R> func = resp -> {
            InputStream inputStream = entityContent(resp);
            if (null != inputStream) {
                return toObj(inputStream, ret);
            }
            return null;
        };
        return execute(post, func);
    }

    static class DisabledValidationTrustManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

    }


    public static String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("[" + obj.getClass().getName() + "] json 序列化异常", e);
        }
    }

    public static <T> T toObj(String json, TypeReference<T> toValueTypeRef) {
        try {
            return OBJECT_MAPPER.readValue(json, toValueTypeRef);
        } catch (Exception e) {
            log.error("json [{}] 反序列化异常", json, e);
        }
        return null;
    }

    public static <T> T toObj(InputStream inputStream, Class<T> cls) {
        try {
            return OBJECT_MAPPER.readValue(inputStream, cls);
        } catch (IOException e) {
            throw new IllegalArgumentException("[" + cls.getName() + "] json 反序列化异常", e);
        }
    }

    public static InputStream entityContent(CloseableHttpResponse response) {
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            return null;
        }
        if (entity.isStreaming()) {
            try {
                return entity.getContent();
            } catch (IOException e) {
                throw new IllegalArgumentException("获取请求数据流异常", e);
            }
        }
        return null;
    }

}
