package cn.nihility.gw.config;

import cn.nihility.gw.trace.TraceContext;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenFeign 装配。
 *
 * <p>底层客户端由 feign-hc5(Apache HttpClient5)接管，超时与连接池参数配置在 application.yaml 的
 * spring.cloud.openfeign 下。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(basePackages = "cn.nihility.gw")
public class FeignConfiguration {

    /**
     * 为 Feign 的编解码器提供 HttpMessageConverters。
     *
     * <p>本项目是纯 WebFlux 应用，而 Spring Boot 的 HttpMessageConvertersAutoConfiguration 带了
     * NotReactiveWebApplicationCondition，在响应式应用里不会生效，于是 Feign 的 SpringDecoder
     * 拿不到这个 Bean，调用时会抛 "No qualifying bean of type 'HttpMessageConverters'"。
     * 这里显式补一个默认实现(含 Jackson)；若将来再引入 Servlet 栈，自动配置会接管，此处自动让位。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpMessageConverters httpMessageConverters() {
        return new HttpMessageConverters();
    }

    /**
     * 把当前线程的 traceId 写入 Feign 请求头，使下游服务复用同一个链路 id。
     *
     * <p>声明成普通 Bean 即可对所有 FeignClient 生效——Spring Cloud OpenFeign 会把主上下文中的
     * RequestInterceptor 收集给每个 client。Feign 调用是同步的，跑在调用方线程上，
     * 因此可以直接从 MDC 取到 traceId；若从线程池发起调用，需要先用 TraceExecutors 包装。</p>
     */
    @Bean
    public RequestInterceptor traceIdRequestInterceptor() {
        return template -> {
            // 接口上已显式声明该头时不覆盖，尊重调用方的显式设置。
            // HTTP 头名不区分大小写，这里必须忽略大小写比较，否则会发出两份 traceId 头
            boolean declared = template.headers().keySet().stream()
                    .anyMatch(TraceContext.TRACE_ID_HEADER::equalsIgnoreCase);
            if (declared) {
                return;
            }
            String traceId = TraceContext.getTraceId();
            if (null != traceId && !traceId.isBlank()) {
                template.header(TraceContext.TRACE_ID_HEADER, traceId);
            }
        };
    }

}
