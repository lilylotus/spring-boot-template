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
    /** 工具类，禁止实例化。 */
    private RpcTelemetry() {}

    /**
     * 按端（client/server）登记的存活资源集合。
     *
     * <p>指标仪表只在首次绑定时注册一次，之后读取的是这里的集合，因此同一进程内多个客户端或
     * 服务端实例的数据会被汇总，实例关闭后自动从集合中移除，不会留下失效仪表。
     */
    private static final Map<String, java.util.Set<Resources>> ACTIVE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 一个客户端或服务端实例对外暴露的运行时指标来源。
     *
     * @param side 所属端，取值为 client 或 server，作为指标标签
     * @param pending 在途请求数的取值函数
     * @param connections 连接数的取值函数
     * @param queue 线程池队列长度的取值函数
     * @param buffered 报文缓冲字节数的取值函数
     */
    public record Resources(
            String side,
            java.util.function.IntSupplier pending,
            java.util.function.IntSupplier connections,
            java.util.function.IntSupplier queue,
            java.util.function.LongSupplier buffered)
            implements AutoCloseable {
        /** 注销本实例的指标来源，使其不再计入对应端的汇总值。 */
        public void close() {
            ACTIVE.getOrDefault(side, java.util.Set.of()).remove(this);
        }
    }

    /**
     * 注册一个实例的运行时指标来源。
     *
     * <p>注册 rpc.pending、rpc.connections、rpc.queue、rpc.buffered.bytes 四个汇总仪表，
     * 以及 Netty 直接内存占用仪表，用于观察在途请求、连接、排队与内存水位。
     * 指标后端不可用时静默跳过，绝不影响实例启动。
     *
     * @param side 所属端，取值为 client 或 server
     * @param pending 在途请求数的取值函数
     * @param connections 连接数的取值函数
     * @param queue 线程池队列长度的取值函数
     * @param buffered 报文缓冲字节数的取值函数
     * @return 资源句柄，实例关闭时需调用 {@link Resources#close()} 注销
     */
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

    /**
     * 累加一个低基数事件计数，如 discovery.update、heartbeat.timeout。
     *
     * @param name 事件名，会被拼上 rpc. 前缀；必须是固定枚举值，禁止拼接请求参数造成指标基数爆炸
     */
    public static void event(String name) {
        try {
            Metrics.counter("rpc." + name).increment();
        } catch (RuntimeException ignored) {
            /* 指标后端不可用不影响调用。 */
        }
    }

    /**
     * 一次 RPC 调用的观测上下文。
     *
     * @param span 本次调用的链路跨度
     * @param context 含本跨度的上下文，用于跨线程恢复与向下游传播
     * @param started 起始纳秒时间戳，用于统计调用耗时
     * @param side 所属端，取值为 client 或 server
     */
    public record Call(Span span, Context context, long started, String side) {
        /**
         * 导出 W3C 链路传播头，随请求发往服务端以串联调用链。
         *
         * @return 传播头键值对；导出失败时返回空映射，不影响调用
         */
        public Map<String, String> carrier() {
            Map<String, String> values = new HashMap<>();
            try {
                W3CTraceContextPropagator.getInstance().inject(context, values, Map::put);
            } catch (RuntimeException ignored) {
                /* 观测失败不改变业务结果。 */
            }
            return values;
        }

        /**
         * 结束本次调用的观测：累加调用计数、记录耗时、标记跨度状态并收尾。
         *
         * @param outcome 调用终态，成功为 success，失败为错误类型名；取值必须低基数
         */
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

    /**
     * 开启一次调用的观测上下文。
     *
     * <p>服务端传入请求携带的传播头以延续客户端链路，客户端传 {@code null} 表示以当前上下文为父跨度。
     * 观测组件不可用时返回一个无效跨度的上下文，保证调用链路本身不受影响。
     *
     * @param side 所属端，取值为 client 或 server
     * @param carrier 上游传入的 W3C 传播头；无上游时为 {@code null}
     * @return 调用观测上下文
     */
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
                                            /**
                                             * 返回传播头中的全部键。
                                             *
                                             * @param values 传播头键值对
                                             * @return 全部键
                                             */
                                            public Iterable<String> keys(
                                                    Map<String, String> values) {
                                                return values.keySet();
                                            }

                                            /**
                                             * 读取指定传播头。
                                             *
                                             * @param values 传播头键值对
                                             * @param key 键名
                                             * @return 对应值；不存在时为 {@code null}
                                             */
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
