package org.example.simple.rpc.monitoring;

import io.micrometer.core.instrument.Metrics;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** 低基数终态指标与 W3C 链路传播；采集失败不能影响调用。 */
public final class RpcTelemetry {
    private RpcTelemetry() {}

    private static final Map<String, java.util.Set<Resources>> ACTIVE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public record Resources(
            String side,
            java.util.function.IntSupplier pending,
            java.util.function.IntSupplier connections,
            java.util.function.IntSupplier queue,
            java.util.function.LongSupplier buffered)
            implements AutoCloseable {
        public void close() {
            ACTIVE.getOrDefault(side, java.util.Set.of()).remove(this);
        }
    }

    public static Resources bind(
            String side,
            java.util.function.IntSupplier pending,
            java.util.function.IntSupplier connections,
            java.util.function.IntSupplier queue,
            java.util.function.LongSupplier buffered) {
        var resources = new Resources(side, pending, connections, queue, buffered);
        var active =
                ACTIVE.computeIfAbsent(
                        side, key -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        active.add(resources);
        try {
            var tags = io.micrometer.core.instrument.Tags.of("side", side);
            Metrics.gauge(
                    "rpc.pending",
                    tags,
                    active,
                    values -> values.stream().mapToInt(r -> r.pending.getAsInt()).sum());
            Metrics.gauge(
                    "rpc.connections",
                    tags,
                    active,
                    values -> values.stream().mapToInt(r -> r.connections.getAsInt()).sum());
            Metrics.gauge(
                    "rpc.queue",
                    tags,
                    active,
                    values -> values.stream().mapToInt(r -> r.queue.getAsInt()).sum());
            Metrics.gauge(
                    "rpc.buffered.bytes",
                    tags,
                    active,
                    values -> values.stream().mapToLong(r -> r.buffered.getAsLong()).sum());
            Metrics.gauge(
                    "rpc.allocator.direct.bytes",
                    io.netty.buffer.PooledByteBufAllocator.DEFAULT,
                    allocator -> allocator.metric().usedDirectMemory());
        } catch (RuntimeException ignored) {
            /* 指标后端不可用不影响启动。 */
        }
        return resources;
    }

    public static void event(String name) {
        try {
            Metrics.counter("rpc." + name).increment();
        } catch (RuntimeException ignored) {
            /* 指标后端不可用不影响调用。 */
        }
    }

    public record Call(Span span, Context context, long started, String side) {
        public Map<String, String> carrier() {
            Map<String, String> values = new HashMap<>();
            try {
                W3CTraceContextPropagator.getInstance().inject(context, values, Map::put);
            } catch (RuntimeException ignored) {
                /* 观测失败不改变业务结果。 */
            }
            return values;
        }

        public void finish(String outcome) {
            try {
                Metrics.counter("rpc.calls", "side", side, "outcome", outcome).increment();
                Metrics.timer("rpc.duration", "side", side, "outcome", outcome)
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                if (!outcome.equals("success")) {
                    span.setStatus(StatusCode.ERROR);
                }
                span.end();
            } catch (RuntimeException ignored) {
                /* 观测失败不改变业务结果。 */
            }
        }
    }

    public static Call start(String side, Map<String, String> carrier) {
        Context parent = Context.current();
        try {
            if (carrier != null) {
                parent =
                        W3CTraceContextPropagator.getInstance()
                                .extract(
                                        parent,
                                        carrier,
                                        new TextMapGetter<>() {
                                            public Iterable<String> keys(
                                                    Map<String, String> values) {
                                                return values.keySet();
                                            }

                                            public String get(
                                                    Map<String, String> values, String key) {
                                                return values.get(key);
                                            }
                                        });
            }
            Span span =
                    GlobalOpenTelemetry.getTracer("org.example.simple.rpc")
                            .spanBuilder("rpc." + side)
                            .setParent(parent)
                            .setSpanKind(side.equals("client") ? SpanKind.CLIENT : SpanKind.SERVER)
                            .startSpan();
            return new Call(span, parent.with(span), System.nanoTime(), side);
        } catch (RuntimeException ignored) {
            return new Call(Span.getInvalid(), parent, System.nanoTime(), side);
        }
    }
}
