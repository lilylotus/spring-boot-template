package org.example.simple.rpc.monitoring;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标与链路传播测试。
 * <p>
 * 覆盖终态计数与低基数标签、资源仪表绑定、异步线程切换后的上下文恢复，
 * 以及链路载体不会携带调用参数等敏感信息。
 */
class RpcTelemetryTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void addRegistry() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void removeRegistry() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    @Test
    void recordsTerminalOutcomeWithLowCardinalityTags() {
        RpcTelemetry.Call call = RpcTelemetry.start("client", null);

        call.finish("TIMEOUT");

        Counter counter = registry.find("rpc.calls").tags("side", "client", "outcome", "TIMEOUT").counter();
        assertNotNull(counter);
        assertEquals(1, counter.count());
        assertEquals(
            Set.of("side", "outcome"),
            counter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet()));
        assertNotNull(registry.find("rpc.duration").tags("side", "client", "outcome", "TIMEOUT").timer());
    }

    @Test
    void countsSuccessAndFailureOutcomesSeparately() {
        RpcTelemetry.start("server", null).finish("success");
        RpcTelemetry.start("server", null).finish("SERVER_BUSY");
        RpcTelemetry.start("server", null).finish("SERVER_BUSY");

        assertEquals(
            1,
            registry.find("rpc.calls").tags("side", "server", "outcome", "success").counter().count());
        assertEquals(
            2,
            registry.find("rpc.calls").tags("side", "server", "outcome", "SERVER_BUSY").counter().count());
    }

    @Test
    void countsNamedEvents() {
        RpcTelemetry.event("heartbeat.timeout");
        RpcTelemetry.event("heartbeat.timeout");

        assertEquals(2, registry.find("rpc.heartbeat.timeout").counter().count());
    }

    @Test
    void bindsResourceGaugesAndStopsReportingAfterClose() {
        try (RpcTelemetry.Resources resources =
                 RpcTelemetry.bind("test-side", () -> 3, () -> 2, () -> 7, () -> 4096L)) {
            assertNotNull(resources);
            assertNotNull(registry.find("rpc.pending").tags("side", "test-side").gauge());
            assertNotNull(registry.find("rpc.connections").tags("side", "test-side").gauge());
            assertNotNull(registry.find("rpc.queue").tags("side", "test-side").gauge());
            assertNotNull(registry.find("rpc.buffered.bytes").tags("side", "test-side").gauge());
            assertEquals(3, registry.find("rpc.pending").tags("side", "test-side").gauge().value());
            assertEquals(4096, registry.find("rpc.buffered.bytes").tags("side", "test-side").gauge().value());
        }

        assertEquals(0, registry.find("rpc.pending").tags("side", "test-side").gauge().value());
    }

    @Test
    void restoresTraceContextAfterThreadSwitch() throws Exception {
        RpcTelemetry.Call call = RpcTelemetry.start("client", null);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            boolean restored = worker.submit(() -> {
                try (Scope scope = call.context().makeCurrent()) {
                    return Context.current() == call.context();
                }
            }).get(5, TimeUnit.SECONDS);

            assertTrue(restored);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void carrierOnlyExposesTraceKeysAndToleratesMalformedInput() {
        RpcTelemetry.Call call = RpcTelemetry.start("server", Map.of("traceparent", "非法内容"));

        Map<String, String> carrier = call.carrier();

        assertTrue(Set.of("traceparent", "tracestate").containsAll(carrier.keySet()));
        call.finish("success");
    }
}
