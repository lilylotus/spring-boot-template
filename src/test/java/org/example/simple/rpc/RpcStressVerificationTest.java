package org.example.simple.rpc;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.example.simple.rpc.client.CallOptions;
import org.example.simple.rpc.client.RpcClient;
import org.example.simple.rpc.common.RpcErrorCode;
import org.example.simple.rpc.common.RpcException;
import org.example.simple.rpc.config.RpcConfig;
import org.example.simple.rpc.server.RpcServer;
import org.example.simple.rpc.server.ServiceRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可复现的压力与故障验证。
 * <p>
 * 默认不执行，使用 {@code gradlew.bat test -Drpc.stress=true} 启用。
 * 验证结果写入 {@code build/reports/rpc-stress.md}，包含吞吐、p95/p99 延迟、
 * 错误率、饱和时的拒绝行为以及负载结束后的资源回收情况。
 */
@EnabledIfSystemProperty(
    named = "rpc.stress",
    matches = "true",
    disabledReason = "未设置 -Drpc.stress=true，压力与故障验证未执行")
class RpcStressVerificationTest {

    private static final String SERVICE_NAME = "压测服务";
    private static final Path REPORT = Path.of("build", "reports", "rpc-stress.md");
    private static final List<String> LINES = Collections.synchronizedList(new ArrayList<>());

    @AfterAll
    static void writeReport() throws IOException {
        Files.createDirectories(REPORT.getParent());
        List<String> content = new ArrayList<>();
        content.add("# Netty RPC 压力与故障验证记录");
        content.add("");
        content.add("运行环境：Java " + Runtime.version().feature()
            + "，可见 CPU " + Runtime.getRuntime().availableProcessors()
            + "，最大堆 " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MiB");
        content.add("");
        content.addAll(LINES);
        Files.write(REPORT, content);
    }

    @Test
    void measuresThroughputAndLatencyUnderSteadyLoad() throws Exception {
        // 压测需要测出传输与调度本身的能力，因此把服务端速率限制调到远高于施加的负载。
        RpcConfig config = RpcConfig.builder()
            .requestsPerSecond(1000000)
            .burstCapacity(100000)
            .requestTimeoutMillis(10000)
            .drainTimeoutMillis(5000)
            .shutdownTimeoutMillis(2000)
            .build();
        int threads = 8;
        int perThread = 1500;

        try (RpcServer server = server(config, () -> "回声");
             RpcClient client = new RpcClient(config)) {
            server.start("127.0.0.1", 0);
            client.connect("127.0.0.1", server.getPort());

            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger failures = new AtomicInteger();
            java.util.Map<String, AtomicInteger> reasons = new java.util.concurrent.ConcurrentHashMap<>();
            ExecutorService callers = Executors.newFixedThreadPool(threads);
            CountDownLatch done = new CountDownLatch(threads);
            long started = System.nanoTime();

            for (int thread = 0; thread < threads; thread++) {
                callers.execute(() -> {
                    try {
                        for (int call = 0; call < perThread; call++) {
                            long begin = System.nanoTime();
                            try {
                                echo(client).get(10, TimeUnit.SECONDS);
                                latencies.add(System.nanoTime() - begin);
                            } catch (Exception error) {
                                failures.incrementAndGet();
                                Throwable cause = error.getCause() == null ? error : error.getCause();
                                reasons.computeIfAbsent(
                                        cause.getMessage(), key -> new AtomicInteger())
                                    .incrementAndGet();
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(5, TimeUnit.MINUTES));
            callers.shutdownNow();
            long elapsedNanos = System.nanoTime() - started;
            int total = threads * perThread;

            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            double throughput = total / (elapsedNanos / 1_000_000_000d);
            record("## 稳态吞吐与延迟");
            record("- 并发线程：" + threads + "，总调用：" + total);
            record("- 吞吐：" + Math.round(throughput) + " 次/秒");
            record("- p50：" + millis(percentile(sorted, 50)) + " 毫秒");
            record("- p95：" + millis(percentile(sorted, 95)) + " 毫秒");
            record("- p99：" + millis(percentile(sorted, 99)) + " 毫秒");
            record("- 错误率：" + String.format("%.4f", failures.get() * 1.0 / total));
            reasons.forEach((reason, count) -> record("  - " + reason + "：" + count.get() + " 次"));
            record("- 负载结束后在途请求：" + client.pendingCount()
                + "，客户端报文占用：" + client.bufferedBytes()
                + " 字节，服务端报文占用：" + server.bufferedBytes() + " 字节");
            record("");

            assertTrue(latencies.size() > 0, "稳态负载下没有任何成功调用");
            assertTrue(awaitRelease(client, server), "负载结束后字节预算未回收");
        }
    }

    @Test
    void reportsBoundedRejectionWhenSaturated() throws Exception {
        // 该场景要观察业务队列上限本身的拒绝行为，因此不让速率限制先行介入。
        RpcConfig config = RpcConfig.builder()
            .businessThreads(1)
            .businessQueueCapacity(1)
            .requestsPerSecond(1000000)
            .burstCapacity(100000)
            .requestTimeoutMillis(10000)
            .drainTimeoutMillis(5000)
            .shutdownTimeoutMillis(2000)
            .build();

        try (RpcServer server = server(config, () -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "回声";
        });
             RpcClient client = new RpcClient(config)) {
            server.start("127.0.0.1", 0);
            client.connect("127.0.0.1", server.getPort());

            int total = 400;
            int admissionRejected = 0;
            List<CompletableFuture<String>> calls = new ArrayList<>();
            for (int index = 0; index < total; index++) {
                try {
                    calls.add(echo(client));
                } catch (RpcException rejected) {
                    // 连接在途额度用尽时，调用在准入阶段就被同步拒绝。
                    admissionRejected++;
                }
            }

            int accepted = 0;
            int rejected = 0;
            for (CompletableFuture<String> call : calls) {
                try {
                    call.get(30, TimeUnit.SECONDS);
                    accepted++;
                } catch (Exception error) {
                    if (error.getCause() instanceof RpcException rpc
                        && rpc.getErrorCode() == RpcErrorCode.SERVER_BUSY) {
                        rejected++;
                    } else {
                        throw error;
                    }
                }
            }

            record("## 饱和拒绝与队列上限");
            record("- 业务线程：1，业务队列容量：1，并发提交：" + total);
            record("- 受理完成：" + accepted
                + "，服务端过载拒绝：" + rejected
                + "，客户端准入拒绝：" + admissionRejected);
            record("- 负载结束后服务端报文占用：" + server.bufferedBytes() + " 字节");
            record("");

            assertEquals(total, accepted + rejected + admissionRejected);
            assertTrue(rejected + admissionRejected > 0, "饱和时必须出现稳定的过载拒绝");
            assertTrue(awaitRelease(client, server), "饱和负载后字节预算未回收");
        }
    }

    @Test
    void releasesResourcesAfterMaximumFrameConcurrency() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .maxMessageLength(1048576)
            .maxChannelWriteBytes(16777216)
            .maxBufferedBytes(33554432)
            .requestsPerSecond(1000000)
            .burstCapacity(100000)
            .requestTimeoutMillis(20000)
            .drainTimeoutMillis(5000)
            .shutdownTimeoutMillis(2000)
            .build();
        String payload = "填".repeat(100000);

        try (RpcServer server = server(config, () -> payload);
             RpcClient client = new RpcClient(config)) {
            server.start("127.0.0.1", 0);
            client.connect("127.0.0.1", server.getPort());

            // 并发规模按端级预算容量选取：40 × 约 0.3 MiB 仍在 16 MiB 预算之内。
            int total = 40;
            java.util.concurrent.atomic.AtomicLong peak = new java.util.concurrent.atomic.AtomicLong();
            Thread sampler = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    peak.accumulateAndGet(server.bufferedBytes(), Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            });
            sampler.setDaemon(true);
            sampler.start();

            List<CompletableFuture<String>> calls = new ArrayList<>();
            for (int index = 0; index < total; index++) {
                calls.add(echo(client));
            }
            int completed = 0;
            for (CompletableFuture<String> call : calls) {
                assertEquals(payload, call.get(30, TimeUnit.SECONDS));
                completed++;
            }
            sampler.interrupt();

            record("## 最大帧并发与资源回收");
            record("- 单响应约 " + payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + " 字节，并发提交：" + total + "，完成：" + completed);
            record("- 服务端报文占用峰值：" + peak.get()
                + " 字节，端级预算：" + config.maxBufferedBytes() + " 字节");
            record("- 结束后客户端占用：" + client.bufferedBytes()
                + " 字节，服务端占用：" + server.bufferedBytes() + " 字节");
            record("");

            assertEquals(total, completed);
            assertTrue(peak.get() <= config.maxBufferedBytes(), "报文字节预算被突破：" + peak.get());
            assertTrue(awaitRelease(client, server), "大报文负载后字节预算未回收");
        }
    }

    private static RpcServer server(RpcConfig config, EchoService implementation) {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, EchoService.class, implementation);
        return new RpcServer(registry, config);
    }

    private static CompletableFuture<String> echo(RpcClient client) {
        return client.invokeAsync(
            SERVICE_NAME,
            "echo",
            String.class,
            new Type[0],
            new Object[0],
            new CallOptions((byte) 1, 20000, null, false, 0));
    }

    private static boolean awaitRelease(RpcClient client, RpcServer server) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (client.bufferedBytes() == 0 && server.bufferedBytes() == 0 && client.pendingCount() == 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile / 100d) - 1);
        return sorted.get(Math.max(0, index));
    }

    private static String millis(long nanos) {
        return String.format("%.3f", nanos / 1_000_000d);
    }

    private static void record(String line) {
        LINES.add(line);
    }

    /**
     * 压测用回声服务。
     */
    interface EchoService {

        /** 返回回声内容。 */
        String echo();
    }
}
