package org.example.simple.rpc;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.example.simple.rpc.client.CallOptions;
import org.example.simple.rpc.client.RpcClient;
import org.example.simple.rpc.common.RpcErrorCode;
import org.example.simple.rpc.common.RpcException;
import org.example.simple.rpc.common.SerializerRegistry;
import org.example.simple.rpc.config.RpcConfig;
import org.example.simple.rpc.discovery.InMemoryServiceDiscovery;
import org.example.simple.rpc.discovery.ServiceInstance;
import org.example.simple.rpc.loadbalance.RoundRobinLoadBalancer;
import org.example.simple.rpc.server.RpcServer;
import org.example.simple.rpc.server.ServiceRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双实例环回集成测试。
 * <p>
 * 同步与异步入口按 JSON 与 Protostuff 两种编码参数化执行，并覆盖节点变化、
 * 连接断开、心跳保活、服务限流与停机排空。
 */
class RpcLoopbackIntegrationTest {

    private static final String SERVICE_NAME = "问候服务";

    private static RpcConfig config() {
        return RpcConfig.builder()
            .requestTimeoutMillis(5000)
            .connectTimeoutMillis(500)
            .drainTimeoutMillis(5000)
            .shutdownTimeoutMillis(2000)
            .build();
    }

    private static RpcServer server(String name, RpcConfig config) throws InterruptedException {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, GreetingService.class, new GreetingServiceImpl(name));
        RpcServer server = new RpcServer(registry, config);
        server.start("127.0.0.1", 0);
        return server;
    }

    private static RpcClient client(RpcConfig config, byte encoding) {
        return new RpcClient(
            config, SerializerRegistry.defaults(), encoding, null, RoundRobinLoadBalancer::new);
    }

    private static String greetSync(RpcClient client, String name) {
        return client.invoke(
            SERVICE_NAME, "greet", String.class, new Class<?>[] {String.class}, new Object[] {name});
    }

    private static CompletableFuture<String> greetAsync(
        RpcClient client, byte encoding, String name) {
        return client.invokeAsync(
            SERVICE_NAME,
            "greet",
            String.class,
            new Type[] {String.class},
            new Object[] {name},
            new CallOptions(encoding, 5000, null, false, 0));
    }

    @ParameterizedTest(name = "序列化编号 {0}")
    @ValueSource(bytes = {1, 2})
    void syncAndAsyncCallsReachBothInstances(byte encoding) throws Exception {
        RpcConfig config = config();
        try (RpcServer first = server("甲", config);
             RpcServer second = server("乙", config);
             RpcClient client = client(config, encoding)) {
            subscribe(client, first, second);

            Set<String> replies = new HashSet<>();
            for (int attempt = 0; attempt < 20; attempt++) {
                replies.add(suffixOf(greetSync(client, "小明")));
            }
            assertEquals(Set.of("甲", "乙"), replies);

            List<CompletableFuture<String>> async = new ArrayList<>();
            for (int attempt = 0; attempt < 20; attempt++) {
                async.add(greetAsync(client, encoding, "小红"));
            }
            Set<String> asyncReplies = new HashSet<>();
            for (CompletableFuture<String> call : async) {
                String reply = call.get(10, TimeUnit.SECONDS);
                assertTrue(reply.startsWith("你好，小红"));
                asyncReplies.add(suffixOf(reply));
            }
            assertEquals(Set.of("甲", "乙"), asyncReplies);
            assertEquals(0, client.pendingCount());
        }
    }

    @ParameterizedTest(name = "序列化编号 {0}")
    @ValueSource(bytes = {1, 2})
    void survivesInstanceRemovalAndDisconnect(byte encoding) throws Exception {
        RpcConfig config = config();
        RpcServer first = server("甲", config);
        try (RpcServer second = server("乙", config);
             RpcClient client = client(config, encoding)) {
            InMemoryServiceDiscovery discovery = subscribe(client, first, second);

            discovery.update(SERVICE_NAME, List.of(instance("b", second)));
            Thread.sleep(300);
            for (int attempt = 0; attempt < 10; attempt++) {
                assertEquals("乙", suffixOf(greetSync(client, "小明")));
            }

            // 已被移除的实例突然断开，不影响仍在使用的连接。
            first.close();
            Thread.sleep(300);
            for (int attempt = 0; attempt < 10; attempt++) {
                assertEquals("乙", suffixOf(greetAsync(client, encoding, "小明").get(10, TimeUnit.SECONDS)));
            }
        } finally {
            first.closeAsync().join();
        }
    }

    @Test
    void heartbeatKeepsIdleConnectionUsable() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .heartbeatIntervalMillis(200)
            .readIdleTimeoutMillis(800)
            .requestTimeoutMillis(5000)
            .drainTimeoutMillis(2000)
            .shutdownTimeoutMillis(2000)
            .build();

        try (RpcServer server = server("甲", config);
             RpcClient client = client(config, (byte) 1)) {
            client.connect("127.0.0.1", server.getPort());
            assertEquals("甲", suffixOf(greetSync(client, "小明")));

            // 空闲时间超过读失活阈值，心跳必须让连接保持可用。
            Thread.sleep(2000);

            assertEquals("甲", suffixOf(greetSync(client, "小明")));
        }
    }

    @Test
    void rateLimitRejectsRequestsBeyondConfiguredRate() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .requestsPerSecond(1)
            .burstCapacity(1)
            .requestTimeoutMillis(5000)
            .drainTimeoutMillis(2000)
            .shutdownTimeoutMillis(2000)
            .build();

        try (RpcServer server = server("甲", config);
             RpcClient client = client(config, (byte) 1)) {
            client.connect("127.0.0.1", server.getPort());

            int accepted = 0;
            int rejected = 0;
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    greetSync(client, "小明");
                    accepted++;
                } catch (RpcException error) {
                    assertEquals(RpcErrorCode.SERVER_BUSY, error.getErrorCode());
                    rejected++;
                }
            }

            assertTrue(accepted > 0, "限流不应拒绝全部请求");
            assertTrue(rejected > 0, "超过速率的请求必须被拒绝");
        }
    }

    @Test
    void gracefulShutdownCompletesInFlightCall() throws Exception {
        RpcConfig config = config();

        try (RpcClient client = client(config, (byte) 1)) {
            RpcServer server = server("甲", config);
            client.connect("127.0.0.1", server.getPort());
            assertEquals("甲", suffixOf(greetSync(client, "小明")));

            CompletableFuture<String> inFlight = client.invokeAsync(
                SERVICE_NAME,
                "slow",
                String.class,
                new Type[0],
                new Object[0],
                new CallOptions((byte) 1, 5000, null, false, 0));
            Thread.sleep(100);

            CompletableFuture<Void> closing = server.closeAsync();

            // 停机期间已经受理的调用必须排空完成，而不是被直接切断。
            assertEquals("慢速完成", inFlight.get(10, TimeUnit.SECONDS));
            closing.get(15, TimeUnit.SECONDS);

            Thread.sleep(300);
            RpcException afterShutdown = assertThrows(
                RpcException.class, () -> greetAsync(client, (byte) 1, "小明"));
            assertEquals(RpcErrorCode.CONNECTION_CLOSED, afterShutdown.getErrorCode());
        }
    }

    private static InMemoryServiceDiscovery subscribe(
        RpcClient client, RpcServer first, RpcServer second) throws InterruptedException {
        InMemoryServiceDiscovery discovery = new InMemoryServiceDiscovery();
        discovery.update(SERVICE_NAME, List.of(instance("a", first), instance("b", second)));
        client.subscribe(SERVICE_NAME, discovery);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                greetSync(client, "预热");
                break;
            } catch (RpcException ignored) {
                Thread.sleep(20);
            }
        }
        Thread.sleep(300);
        return discovery;
    }

    private static ServiceInstance instance(String id, RpcServer server) {
        return new ServiceInstance(id, "127.0.0.1", server.getPort(), 1);
    }

    private static String suffixOf(String reply) {
        return reply.substring(reply.length() - 1);
    }

    /**
     * 测试用问候服务。
     */
    interface GreetingService {

        /**
         * 返回带实例标识的问候。
         *
         * @param name 姓名
         * @return 问候内容
         */
        String greet(String name);

        /** 执行一次耗时调用。 */
        String slow();
    }

    /**
     * 按实例名返回问候的测试实现。
     */
    record GreetingServiceImpl(String instanceName) implements GreetingService {

        @Override
        public String greet(String name) {
            return "你好，" + name + instanceName;
        }

        @Override
        public String slow() {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "慢速完成";
        }
    }
}
