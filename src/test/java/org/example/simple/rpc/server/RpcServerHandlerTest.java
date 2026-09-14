package org.example.simple.rpc.server;

import java.util.concurrent.*;
import org.example.simple.rpc.client.*;
import org.example.simple.rpc.common.*;
import org.example.simple.rpc.config.RpcConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RpcServerHandlerTest {
    interface Service { String block(); }
    @Test void rejectsWorkWhenBusinessCapacityIsExhausted() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ServiceRegistry registry = new ServiceRegistry();
        registry.register("服务", Service.class, (Service) () -> {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "完成";
        });
        RpcConfig config = RpcConfig.builder().businessThreads(1).businessQueueCapacity(1).drainTimeoutMillis(100).build();
        try (RpcServer server = new RpcServer(registry, config); RpcClient client = new RpcClient(config)) {
            server.start("127.0.0.1", 0); client.connect("127.0.0.1", server.getPort());
            var first = client.invokeAsync("服务", "block", String.class, new Class<?>[0], new Object[0]);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var calls = new java.util.ArrayList<CompletableFuture<String>>();
            for (int i = 0; i < 5; i++) { calls.add(client.invokeAsync("服务", "block", String.class,
                new Class<?>[0], new Object[0])); }
            assertThrows(ExecutionException.class,
                () -> CompletableFuture.anyOf(calls.toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS));
            release.countDown();
            long rejected = 0;
            for (var call : calls) {
                try { call.get(3, TimeUnit.SECONDS); }
                catch (ExecutionException e) { assertEquals(RpcErrorCode.SERVER_BUSY,
                    ((RpcException) e.getCause()).getErrorCode()); rejected++; }
            }
            release.countDown(); assertTrue(rejected > 0); assertEquals("完成", first.get());
        } finally { release.countDown(); }
    }
}
