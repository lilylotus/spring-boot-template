package org.example.simple.rpc;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.example.simple.rpc.client.*;
import org.example.simple.rpc.common.*;
import org.example.simple.rpc.config.RpcConfig;
import org.example.simple.rpc.discovery.*;
import org.example.simple.rpc.loadbalance.*;
import org.example.simple.rpc.server.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RpcClusterTest {
    interface Service { String identify(); }
    private RpcServer server(String name) throws Exception {
        ServiceRegistry registry = new ServiceRegistry(); registry.register("服务", Service.class, (Service) () -> name);
        RpcServer server = new RpcServer(registry, RpcConfig.builder().drainTimeoutMillis(100).build());
        server.start("127.0.0.1", 0); return server;
    }
    @Test void dynamicallyAddsAndRemovesInstancesForBothEncodings() throws Exception {
        try (RpcServer first = server("甲"); RpcServer second = server("乙"); RpcClient client = new RpcClient()) {
            InMemoryServiceDiscovery discovery = new InMemoryServiceDiscovery();
            ServiceInstance a = new ServiceInstance("a", "127.0.0.1", first.getPort(), 1);
            ServiceInstance b = new ServiceInstance("b", "127.0.0.1", second.getPort(), 1);
            discovery.update("服务", List.of(a, b)); client.subscribe("服务", discovery);
            Set<String> seen = new HashSet<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (seen.size() < 2 && System.nanoTime() < deadline) {
                try { seen.add(client.invoke("服务", "identify", String.class, new Class<?>[0], new Object[0])); }
                catch (RpcException ignored) { Thread.sleep(10); }
            }
            assertEquals(Set.of("甲", "乙"), seen);
            discovery.update("服务", List.of(b));
            Thread.sleep(150);
            for (byte encoding : new byte[]{1, 2}) {
                for (int i = 0; i < 10; i++) {
                    assertEquals("乙", client.invokeAsync("服务", "identify", String.class,
                        new Class<?>[0], new Object[0], new CallOptions(encoding, 2000, null, false, 0)).get());
                }
            }
            discovery.update("服务", List.of()); Thread.sleep(100);
            assertThrows(RpcException.class, () -> client.invoke("服务", "identify", String.class,
                new Class<?>[0], new Object[0]));
        }
    }
    @Test void balancesWeightsAndKeepsHashKeysStable() {
        EmbeddedChannel a = new EmbeddedChannel(), b = new EmbeddedChannel();
        try {
            var first = new LoadBalancer.Candidate(new ServiceInstance("a", "localhost", 1, 1), a);
            var second = new LoadBalancer.Candidate(new ServiceInstance("b", "localhost", 2, 3), b);
            var candidates = List.of(first, second);
            var weighted = new WeightedRoundRobinLoadBalancer();
            int count = 0;
            for (int i = 0; i < 400; i++) { if (weighted.select(candidates, null) == first) { count++; } }
            assertEquals(100, count);
            var hash = new ConsistentHashLoadBalancer();
            for (int i = 0; i < 100; i++) {
                assertEquals(hash.select(candidates, "键" + i), hash.select(List.of(second, first), "键" + i));
            }
            assertThrows(IllegalArgumentException.class, () -> hash.select(candidates, null));
        } finally { a.finishAndReleaseAll(); b.finishAndReleaseAll(); }
    }
}
