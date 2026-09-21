package cn.nihility.gw.feign;

import cn.nihility.gw.trace.TraceContext;
import feign.Client;
import feign.hc5.ApacheHttp5Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 OpenFeign 的三项配置：底层用 HttpClient5、traceId 透传、读超时 5 秒。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"
        })
class FeignTraceTest {

    /** 读超时配置值(毫秒)，与 application.yaml 中的 read-timeout 一致。 */
    private static final long READ_TIMEOUT_MILLIS = 5000L;

    static {
        // @FeignClient 的 url 在 Bean 创建阶段就会被解析，而 RANDOM_PORT 的 local.server.port
        // 要等容器 finishRefresh 之后才可用，届时占位符已经解析失败。
        // 所以这里先自行占一个空闲端口，用 DEFINED_PORT 固定下来。
        int port = reserveFreePort();
        System.setProperty("server.port", String.valueOf(port));
        System.setProperty("trace.probe.url", "http://localhost:" + port);
    }

    /** 借助端口 0 让系统分配一个空闲端口，随即释放供容器使用。 */
    private static int reserveFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("无法分配测试端口", ex);
        }
    }

    /** 清理测试写入的系统属性，避免影响同一 JVM 中的其它测试。 */
    @AfterAll
    static void clearSystemProperties() {
        System.clearProperty("server.port");
        System.clearProperty("trace.probe.url");
    }

    @Autowired
    private FeignTraceProbeClient probeClient;

    @Autowired
    private Client feignClient;

    @Autowired
    private FeignClientProperties feignClientProperties;

    /** 每个用例结束后清理 MDC，避免 traceId 残留影响后续用例。 */
    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    /** 底层客户端必须是 HttpClient5，而不是 Feign 默认的 HttpURLConnection。 */
    @Test
    void shouldUseApacheHttpClient5() {
        Client actual = feignClient;
        // 引入 loadbalancer 后，真正的客户端会被包一层负载均衡客户端，这里需要先拆开
        if (actual instanceof FeignBlockingLoadBalancerClient wrapper) {
            actual = wrapper.getDelegate();
        }
        assertInstanceOf(ApacheHttp5Client.class, actual);
    }

    /**
     * application.yaml 里的超时配置必须真正绑定到 Feign 的 Request.Options 上。
     *
     * <p>连接超时无法用一次真实调用稳定地验证(需要一个不可路由的地址，结果依赖网络环境)，
     * 所以这里断言配置绑定结果——真正的风险是 yaml 的 key 写错被静默忽略。
     * 读超时是否真正生效，由 shouldTimeOutAfterConfiguredReadTimeout 实测覆盖。</p>
     */
    @Test
    void shouldBindConfiguredTimeouts() {
        String defaultConfigKey = feignClientProperties.getDefaultConfig();
        FeignClientProperties.FeignClientConfiguration defaultConfig =
                feignClientProperties.getConfig().get(defaultConfigKey);

        assertNotNull(defaultConfig, "没有读取到 default 配置，检查 yaml 中的 spring.cloud.openfeign.client.config");
        assertEquals(3000, defaultConfig.getConnectTimeout());
        assertEquals(5000, defaultConfig.getReadTimeout());
    }

    /** 调用方线程上的 traceId 必须随 Feign 请求头发给下游。 */
    @Test
    void shouldPropagateTraceIdHeader() {
        String traceId = TraceContext.setTraceId("feigntraceid0123456789abcdef0123");
        assertEquals(traceId, probeClient.echoTrace());
    }

    /** 下游迟迟不响应时，必须在 read-timeout 约定的 5 秒左右失败，而不是一直挂着。 */
    @Test
    void shouldTimeOutAfterConfiguredReadTimeout() {
        long startNanos = System.nanoTime();
        assertThrows(Exception.class, () -> probeClient.slow());
        long costMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(
                costMillis >= READ_TIMEOUT_MILLIS - 500L && costMillis < READ_TIMEOUT_MILLIS + 3000L,
                "读超时应在 5 秒左右触发，实际耗时 " + costMillis + "ms");
    }

    /** 注册测试专用的下游探针接口。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        FeignProbeController feignProbeController() {
            return new FeignProbeController();
        }

    }

    /** 模拟被 Feign 调用的下游服务。 */
    @RestController
    static class FeignProbeController {

        /** 原样回显收到的 traceId 头。 */
        @GetMapping("/feign-probe/echo-trace")
        String echoTrace(@RequestHeader(value = TraceContext.TRACE_ID_HEADER, required = false) String traceId) {
            return traceId == null ? "" : traceId;
        }

        /**
         * 延迟远超 read-timeout，保证一定触发超时。
         *
         * <p>用 Mono.delay 而不是 Thread.sleep：这是 WebFlux 应用，sleep 会占住一个事件循环线程，
         * 同时跑的其它用例会被一起拖住。</p>
         */
        @GetMapping("/feign-probe/slow")
        Mono<String> slow() {
            return Mono.delay(Duration.ofSeconds(8L)).thenReturn("never");
        }

    }

}
