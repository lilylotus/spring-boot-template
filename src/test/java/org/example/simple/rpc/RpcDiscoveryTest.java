package org.example.simple.rpc;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import org.example.simple.rpc.client.RpcClient;
import org.example.simple.rpc.common.RpcException;
import org.example.simple.rpc.config.RpcConfig;
import org.example.simple.rpc.discovery.InMemoryServiceDiscovery;
import org.example.simple.rpc.discovery.ServiceDiscovery;
import org.example.simple.rpc.discovery.ServiceInstance;
import org.example.simple.rpc.server.RpcServer;
import org.example.simple.rpc.server.ServiceRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务发现语义测试。
 * <p>
 * 覆盖订阅失败后的缓存有效期、缓存过期后的拒绝、有效空快照与订阅异常的区分、
 * 实例移除后迟到连接不得复活，以及单条连接断开只影响该连接。
 */
class RpcDiscoveryTest {

    private static final String SERVICE_NAME = "问候服务";

    private static RpcConfig config() {
        return RpcConfig.builder()
            .requestTimeoutMillis(3000)
            .connectTimeoutMillis(300)
            .drainTimeoutMillis(500)
            .shutdownTimeoutMillis(1000)
            .build();
    }

    private static RpcServer server(String name) throws InterruptedException {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, GreetingService.class, (GreetingService) () -> name);
        RpcServer server = new RpcServer(registry, config());
        server.start("127.0.0.1", 0);
        return server;
    }

    private static String invoke(RpcClient client) {
        return client.invoke(SERVICE_NAME, "greet", String.class, new Class<?>[0], new Object[0]);
    }

    @Test
    void keepsServingFromCacheWhenSubscriptionFails() throws Exception {
        try (RpcServer server = server("甲");
             RpcClient client = new RpcClient(config())) {
            ControllableDiscovery discovery = new ControllableDiscovery(
                List.of(new ServiceInstance("a", "127.0.0.1", server.getPort(), 1)));
            client.subscribe(SERVICE_NAME, discovery);
            assertTrue(awaitReady(client));

            discovery.fail(new IllegalStateException("注册中心不可用"));
            Thread.sleep(200);

            // 订阅失败但缓存尚未过期，已有健康连接仍然可用。
            assertEquals("甲", invoke(client));
        }
    }

    @Test
    void rejectsCallsAfterDiscoveryCacheExpires() throws Exception {
        try (RpcServer server = server("甲");
             RpcClient client = new RpcClient(config())) {
            ControllableDiscovery discovery = new ControllableDiscovery(
                List.of(new ServiceInstance("a", "127.0.0.1", server.getPort(), 1)));
            client.subscribe(SERVICE_NAME, discovery);
            assertTrue(awaitReady(client));

            discovery.fail(new IllegalStateException("注册中心不可用"));
            Thread.sleep(200);
            expireDiscoveryFailure(client);

            RpcException error = assertThrows(RpcException.class, () -> invoke(client));
            assertTrue(error.getMessage().contains("服务发现缓存已过期"));
        }
    }

    @Test
    void treatsEmptySnapshotAsInstanceRemovalRatherThanFailure() throws Exception {
        try (RpcServer server = server("甲");
             RpcClient client = new RpcClient(config())) {
            InMemoryServiceDiscovery discovery = new InMemoryServiceDiscovery();
            discovery.update(
                SERVICE_NAME, List.of(new ServiceInstance("a", "127.0.0.1", server.getPort(), 1)));
            client.subscribe(SERVICE_NAME, discovery);
            assertTrue(awaitReady(client));

            discovery.update(SERVICE_NAME, List.of());
            Thread.sleep(300);

            // 有效空快照立即摘除全部实例，与订阅异常是不同语义。
            RpcException error = assertThrows(RpcException.class, () -> invoke(client));
            assertTrue(error.getMessage().contains("没有可用服务实例"));
        }
    }

    @Test
    void removedInstanceIsNotRevivedByLateConnection() throws Exception {
        try (RpcClient client = new RpcClient(config())) {
            int port = freePort();
            InMemoryServiceDiscovery discovery = new InMemoryServiceDiscovery();
            // 实例地址此时还没有监听，连接会失败并进入重连退避。
            discovery.update(SERVICE_NAME, List.of(new ServiceInstance("a", "127.0.0.1", port, 1)));
            client.subscribe(SERVICE_NAME, discovery);
            Thread.sleep(200);

            discovery.update(SERVICE_NAME, List.of());
            Thread.sleep(100);

            try (RpcServer late = new RpcServer(registryOf("迟到"), config())) {
                late.start("127.0.0.1", port);
                Thread.sleep(1000);

                RpcException error = assertThrows(RpcException.class, () -> invoke(client));
                assertTrue(error.getMessage().contains("没有可用服务实例"));
            }
        }
    }

    @Test
    void disconnectOfOneInstanceKeepsOtherInstanceUsable() throws Exception {
        RpcServer first = server("甲");
        try (RpcServer second = server("乙");
             RpcClient client = new RpcClient(config())) {
            InMemoryServiceDiscovery discovery = new InMemoryServiceDiscovery();
            discovery.update(SERVICE_NAME, List.of(
                new ServiceInstance("a", "127.0.0.1", first.getPort(), 1),
                new ServiceInstance("b", "127.0.0.1", second.getPort(), 1)));
            client.subscribe(SERVICE_NAME, discovery);
            assertTrue(awaitReady(client));

            first.close();
            Thread.sleep(500);

            // 单条连接断开只影响该连接，剩余实例继续提供服务。
            for (int attempt = 0; attempt < 10; attempt++) {
                assertEquals("乙", invoke(client));
            }
        } finally {
            first.closeAsync().join();
        }
    }

    private static ServiceRegistry registryOf(String name) {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, GreetingService.class, (GreetingService) () -> name);
        return registry;
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean awaitReady(RpcClient client) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                invoke(client);
                return true;
            } catch (RpcException ignored) {
                Thread.sleep(20);
            }
        }
        return false;
    }

    /**
     * 将发现失败时间回拨到缓存有效期之外。
     *
     * <p>缓存有效期为固定的 30 秒，测试不可能真实等待，因此直接回拨记录的失败时刻。
     *
     * @param client 目标客户端
     * @throws ReflectiveOperationException 字段不可访问时抛出
     */
    @SuppressWarnings("unchecked")
    private static void expireDiscoveryFailure(RpcClient client) throws ReflectiveOperationException {
        Field field = RpcClient.class.getDeclaredField("discoveryFailure");
        field.setAccessible(true);
        synchronized (client) {
            Map<String, Long> failures = (Map<String, Long>) field.get(client);
            failures.put(SERVICE_NAME, System.nanoTime() - TimeUnit.SECONDS.toNanos(31));
        }
    }

    /**
     * 可手动触发订阅失败的发现实现。
     */
    private static final class ControllableDiscovery implements ServiceDiscovery {

        private final List<ServiceInstance> initial;
        private Consumer<Throwable> errors;

        ControllableDiscovery(List<ServiceInstance> initial) {
            this.initial = initial;
        }

        void fail(Throwable error) {
            errors.accept(error);
        }

        @Override
        public AutoCloseable subscribe(
            String service,
            Consumer<List<ServiceInstance>> instances,
            Consumer<Throwable> errors) {
            this.errors = errors;
            instances.accept(initial);
            return () -> { };
        }
    }

    /**
     * 测试用问候服务。
     */
    interface GreetingService {

        /** 返回所在实例的标识。 */
        String greet();
    }
}
