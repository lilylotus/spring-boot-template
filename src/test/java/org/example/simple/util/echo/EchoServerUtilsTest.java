package org.example.simple.util.echo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link EchoServerUtils} 测试：参数解析与基于本地测试服务端的压测行为，不访问外部地址。
 */
class EchoServerUtilsTest {

    /** 测试使用的连接数。 */
    private static final int CLIENTS = 20;

    @Test
    void parsesDefaultConfig() {
        EchoLoadTestConfig config = EchoServerUtils.parseArgs();

        assertEquals("10.10.10.206", config.host());
        assertEquals(19090, config.port());
        assertEquals(1000, config.clients());
        assertEquals(60, config.durationSeconds());
        assertEquals(1000, config.connectRate());
        assertEquals(1000, config.intervalMillis());
        assertEquals(16, config.minMessageSize());
        assertEquals(128, config.maxMessageSize());
        assertTrue(config.localAddresses().isEmpty());
        assertFalse(config.isForever());
        assertEquals(config, EchoLoadTestConfig.defaults());
    }

    @Test
    void parsesOverriddenArgs() {
        EchoLoadTestConfig config = EchoServerUtils.parseArgs(
                "--host=127.0.0.1", "--port=9000", "--clients=5000", "--duration=0",
                "--interval-ms=0", "--min-message-size=32", "--max-message-size=256",
                "--local-addresses=10.0.0.1, 10.0.0.2");

        assertEquals("127.0.0.1", config.host());
        assertEquals(9000, config.port());
        assertEquals(5000, config.clients());
        assertTrue(config.isForever());
        assertEquals(0, config.intervalMillis());
        assertEquals(32, config.minMessageSize());
        assertEquals(256, config.maxMessageSize());
        assertEquals(List.of("10.0.0.1", "10.0.0.2"), config.localAddresses());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "--unknown=1", "clients=1", "--clients", "--clients=abc", "--clients=0", "--min-message-size=0",
        "--max-message-size=0", "--message-size=64", "--min-message-size=200",
        "--connect-rate=0", "--duration=-1", "--interval-ms=-1", "--port=0", "--port=65536", "--host= ",
        "--local-addresses=10.0.0.1,,10.0.0.2"
    })
    void rejectsInvalidArgs(String arg) {
        assertThrows(IllegalArgumentException.class, () -> EchoServerUtils.parseArgs(arg));
    }

    @Test
    void rejectsMinMessageSizeGreaterThanMax() {
        assertThrows(IllegalArgumentException.class,
                () -> EchoServerUtils.parseArgs("--min-message-size=64", "--max-message-size=32"));
        EchoLoadTestConfig fixed = EchoLoadTestConfig.builder().fixedMessageSize(64).build();
        assertEquals(64, fixed.minMessageSize());
        assertEquals(64, fixed.maxMessageSize());
    }

    @Test
    void generatesRandomAlphanumericPayloadWithinRange() {
        Set<Integer> lengths = new HashSet<>();
        Set<String> contents = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            byte[] payload = EchoClientHandler.randomPayload(16, 128);
            assertTrue(payload.length >= 16 && payload.length <= 128, "长度越界：" + payload.length);
            String text = new String(payload, StandardCharsets.US_ASCII);
            assertTrue(text.matches("[0-9A-Za-z]+"), text);
            lengths.add(payload.length);
            contents.add(text);
        }
        assertTrue(lengths.contains(16) && lengths.contains(128), "应覆盖上下边界长度");
        assertTrue(lengths.size() > 100, "长度应随机分布");
        assertEquals(2000, contents.size(), "内容应随机变化");
        assertEquals(64, EchoClientHandler.randomPayload(64, 64).length);
    }

    @Test
    @Timeout(30)
    void establishesAllConnectionsAndCompletesRequests() {
        try (LocalEchoTestServer server = new LocalEchoTestServer(LocalEchoTestServer.Mode.ECHO)) {
            EchoLoadReport report = EchoServerUtils.run(localConfig(server.port())
                    .durationSeconds(2)
                    .build());

            assertEquals(CLIENTS, report.initiated());
            assertEquals(CLIENTS, report.peakActive());
            assertEquals(CLIENTS, report.active());
            assertEquals(0, report.connectFailed());
            assertTrue(report.requestsCompleted() > CLIENTS, "请求完成数应持续增长");
            assertEquals(0, report.contentErrors());
            assertEquals(0, report.responseTimeouts());
            assertEquals(0, report.passiveClosed(), "工具主动关闭不应计入被动断开");
            assertTrue(report.bytesReceived() > 0);
        }
    }

    @Test
    @Timeout(30)
    void runsUntilStoppedInForeverMode() throws InterruptedException {
        try (LocalEchoTestServer server = new LocalEchoTestServer(LocalEchoTestServer.Mode.ECHO)) {
            EchoLoadTestSession session = EchoServerUtils.start(localConfig(server.port())
                    .durationSeconds(0)
                    .build());

            waitUntil(() -> session.snapshot().active() == CLIENTS);
            assertFalse(session.awaitTermination(500, TimeUnit.MILLISECONDS), "一直运行模式不应自行结束");
            assertFalse(session.isStopping());

            EchoLoadReport report = session.stop();
            EchoLoadReport again = session.stop();

            assertSame(report, again);
            assertTrue(session.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(CLIENTS, report.peakActive());
            assertTrue(report.requestsCompleted() > 0);
            assertEquals(0, report.passiveClosed());
        }
    }

    @Test
    @Timeout(30)
    void countsConnectFailuresWhenNothingListens() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        EchoLoadReport report = EchoServerUtils.run(localConfig(port)
                .clients(5)
                .connectTimeoutMillis(1000)
                .durationSeconds(4)
                .build());

        assertEquals(5, report.initiated());
        assertEquals(5, report.connectFailed());
        assertEquals(0, report.peakActive());
        assertEquals(0, report.requestsCompleted());
    }

    @Test
    @Timeout(30)
    void countsContentErrorsWhenEchoIsCorrupted() {
        try (LocalEchoTestServer server = new LocalEchoTestServer(LocalEchoTestServer.Mode.CORRUPT)) {
            EchoLoadReport report = EchoServerUtils.run(localConfig(server.port())
                    .clients(5)
                    .durationSeconds(2)
                    .build());

            assertEquals(5, report.contentErrors());
            assertEquals(0, report.requestsCompleted());
            assertEquals(0, report.passiveClosed(), "内容错误由工具主动关闭，不计入被动断开");
        }
    }

    @Test
    @Timeout(30)
    void countsResponseTimeoutsWhenServerDoesNotEcho() {
        try (LocalEchoTestServer server = new LocalEchoTestServer(LocalEchoTestServer.Mode.SILENT)) {
            EchoLoadReport report = EchoServerUtils.run(localConfig(server.port())
                    .clients(5)
                    .responseTimeoutMillis(300)
                    .durationSeconds(2)
                    .build());

            assertEquals(5, report.responseTimeouts());
            assertEquals(0, report.requestsCompleted());
            assertEquals(0, report.contentErrors());
        }
    }

    @Test
    void formatsIntervalLineAndSummary() {
        EchoLoadReport previous = new EchoLoadReport(
                1000, 10, 10, 0, 10, 10, 0, 0, 100, 100, 0, 0, 6400, 6400, 100_000_000, 2_000_000);
        EchoLoadReport current = new EchoLoadReport(
                2000, 10, 10, 0, 10, 10, 0, 0, 300, 300, 0, 0, 19200, 19200, 500_000_000, 3_000_000);

        String line = current.toIntervalLine(previous, 3_000_000);

        assertTrue(line.contains("在线=10/10"), line);
        assertTrue(line.contains("QPS=200.0"), line);
        assertTrue(line.contains("平均=2.00ms"), line);
        assertTrue(line.contains("最大=3.00ms"), line);
        assertTrue(current.toSummary().contains("峰值在线连接    10"));
        assertEquals("1.5KB", EchoLoadReport.formatBytes(1536));
    }

    /** 构建指向本地端口、快速发送的测试配置。 */
    private static EchoLoadTestConfig.Builder localConfig(int port) {
        return EchoLoadTestConfig.builder()
                .host("127.0.0.1")
                .port(port)
                .clients(CLIENTS)
                .intervalMillis(20)
                .ioThreads(2);
    }

    /** 轮询等待条件成立，最长 10 秒。 */
    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("等待条件超时");
            }
            Thread.sleep(20);
        }
    }
}
