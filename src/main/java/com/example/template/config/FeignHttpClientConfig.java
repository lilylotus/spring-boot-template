package com.example.template.config;

import com.example.template.openfeign.FeignPoolProperties;
import feign.Client;
import feign.hc5.ApacheHttp5Client;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.ssl.TLS;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Feign底层HttpClient连接池配置，Feign底层HttpClient连接池配置
 * 适配版本: org.apache.httpcomponents.client5:httpclient5:5.6.4
 */
//@Configuration
//现在的写法(整体绑定到一个Properties类)
@EnableConfigurationProperties(FeignPoolProperties.class)
public class FeignHttpClientConfig {

    @Bean
    public PoolingHttpClientConnectionManager poolingHttpClientConnectionManager(FeignPoolProperties feignPoolProperties) {
        return PoolingHttpClientConnectionManagerBuilder.create()
            // 5.2+版本起，SSL配置方式改为TlsSocketStrategy，
            // 老版本的setSSLSocketFactory(LayeredConnectionSocketFactory)已废弃
            .setTlsSocketStrategy(
                ClientTlsStrategyBuilder.create()
                    .setSslContext(SSLContexts.createSystemDefault())
                    .setTlsVersions(TLS.V_1_3, TLS.V_1_2)
                    .buildClassic()
            )
            .setMaxConnTotal(feignPoolProperties.getMaxTotal())
            .setMaxConnPerRoute(feignPoolProperties.getMaxPerRoute())
            .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT)
            .setConnPoolPolicy(PoolReusePolicy.LIFO)
            .setDefaultSocketConfig(
                SocketConfig.custom()
                    .setSoTimeout(Timeout.ofSeconds(5))
                    .setSoKeepAlive(true)
                    .build()
            )
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(3))
                    .setSocketTimeout(Timeout.ofSeconds(5))
                    .setValidateAfterInactivity(TimeValue.ofSeconds(10))
                    .build()
            )
            .build();
    }

    @Bean
    public CloseableHttpClient httpClient(PoolingHttpClientConnectionManager connectionManager) {
        RequestConfig requestConfig = RequestConfig.custom()
            // 从连接池获取可用连接的最大等待时间(连接池打满时排队等待的超时)
            .setConnectionRequestTimeout(Timeout.ofSeconds(3))
            .setResponseTimeout(Timeout.ofSeconds(5))
            .build();

        return HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            // 定期清理空闲超过30秒的连接
            .evictIdleConnections(TimeValue.ofSeconds(30))
            // 清理已超过connectionTimeToLive的过期连接
            .evictExpiredConnections()
            // 网络层自动重试(区别于业务层重试)，处理连接刚建立就被服务端关闭等瞬时故障
            .setRetryStrategy(new DefaultHttpRequestRetryStrategy(2, TimeValue.ofMilliseconds(500)))
            .build();
    }

    /**
     * 新增这个Bean，手动把CloseableHttpClient包装成Feign认识的Client接口实现
     * 这就是问题所在——官方自动配置"让位"了，但没人接手做"包装成feign.Client"这一步
     * <p>
     * Spring Cloud官方文档里说的"你可以自定义CloseableHttpClient Bean来自定义HttpClient行为"
     * 隐含前提是：官方本来会自动把这个Bean包装成feign.Client接口实现（也就是ApacheHttp5Client）。
     * 一旦你自己提供了CloseableHttpClient，自动配置类整体被跳过（Did not match），
     * 连带着它内部原本要做的"包装成ApacheHttp5Client并注册为feign.Client"这个动作也一起没有执行。
     * <p>
     * 所以最终Spring容器里根本没有一个 feign.Client 类型的Bean被正确设置成基于HttpClient5实现的，
     * Feign只能退回使用它自己最基础的默认实现（JDK的HttpURLConnection），
     * 这正好解释了你最早看到的 User-Agent: Java/21.0.1。
     * <p>
     * 修复方式：自己显式提供 feign.Client Bean，把 CloseableHttpClient 包装成 ApacheHttp5Client
     * <p>
     * 在你的 FeignHttpClientConfig 里，除了原来那个 CloseableHttpClient Bean，
     * 再显式加一个 feign.Client Bean，手动完成官方原本要做的这一步包装：
     */
    @Bean
    public Client feignClient(CloseableHttpClient httpClient) {
        return new ApacheHttp5Client(httpClient);
    }

}
