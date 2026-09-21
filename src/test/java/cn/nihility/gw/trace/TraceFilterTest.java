package cn.nihility.gw.trace;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 traceId 的写入、透传、跨线程传递，以及接口耗时日志的格式。
 *
 * <p>断言直接读取真实的日志文件，这样连「异步 appender 是否真的把日志落盘」也一并覆盖到了。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"
        })
class TraceFilterTest {

    /** 日志文件位置，与 log4j2-spring.xml 中的默认配置一致。 */
    private static final Path LOG_FILE = Path.of("logs", "CloudGateway.log");

    /** 等待异步日志落盘的最长时间(毫秒)。 */
    private static final long AWAIT_TIMEOUT_MILLIS = 5000L;

    @Autowired
    private TestRestTemplate restTemplate;

    /** 完整链路：上游 traceId 被复用、响应头回写、耗时日志格式、跨线程 traceId 一致。 */
    @Test
    void shouldTraceAcrossThreadsAndLogCost() throws Exception {
        String upstreamTraceId = "testtraceid0123456789abcdef01234";

        HttpHeaders headers = new HttpHeaders();
        headers.set(TraceContext.TRACE_ID_HEADER, upstreamTraceId);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // 探针接口负责验证跨线程；/welcome 是项目真实接口，用来验证耗时日志格式
        restTemplate.exchange("/trace-probe", HttpMethod.GET, request, String.class);
        ResponseEntity<String> response = restTemplate.exchange(
                "/welcome", HttpMethod.GET, request, String.class);

        // 上游已带 traceId 时必须沿用，而不是另生成一个，否则链路会断开
        assertEquals(upstreamTraceId, response.getHeaders().getFirst(TraceContext.TRACE_ID_HEADER));

        List<String> traced = awaitLines(upstreamTraceId);

        assertTrue(
                traced.stream().anyMatch(line -> line.contains("GET /welcome cost [") && line.endsWith("]ms")),
                "缺少接口耗时日志，实际内容: " + traced);
        assertTrue(
                traced.stream().anyMatch(line -> line.contains("POOL-THREAD-MARKER")),
                "线程池任务没有继承 traceId，实际内容: " + traced);
        assertTrue(
                traced.stream().anyMatch(line -> line.contains("CHILD-THREAD-MARKER")),
                "子线程没有继承 traceId，实际内容: " + traced);
    }

    /** 轮询日志文件，直到出现含指定 traceId 的行，避免用固定 sleep 造成偶发失败。 */
    private List<String> awaitLines(String traceId) throws Exception {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        List<String> matched = List.of();
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(LOG_FILE)) {
                matched = Files.readAllLines(LOG_FILE, StandardCharsets.UTF_8).stream()
                        .filter(line -> line.contains(traceId))
                        .toList();
                if (matched.size() >= 4) {
                    return matched;
                }
            }
            Thread.sleep(100L);
        }
        return matched;
    }

    /** 注册测试专用的跨线程探针接口。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class TraceProbeConfiguration {

        @Bean
        TraceProbeController traceProbeController() {
            return new TraceProbeController();
        }

    }

    /** 跨线程探针：处理过程中分别向线程池和子线程各打一条日志。 */
    @RestController
    static class TraceProbeController {

        private static final Logger LOG = LoggerFactory.getLogger(TraceProbeController.class);

        @GetMapping("/trace-probe")
        String probe() throws Exception {
            // 场景一：线程池复用线程，靠 TraceExecutors 显式传递上下文
            ExecutorService pool = TraceExecutors.wrap(Executors.newFixedThreadPool(1));
            pool.submit(() -> LOG.info("POOL-THREAD-MARKER")).get();
            pool.shutdown();
            pool.awaitTermination(2L, TimeUnit.SECONDS);

            // 场景二：临时子线程，靠 log4j2.component.properties 里的 InheritableThreadLocal 继承
            Thread child = new Thread(() -> LOG.info("CHILD-THREAD-MARKER"));
            child.start();
            child.join();

            return "probe";
        }

    }

}
