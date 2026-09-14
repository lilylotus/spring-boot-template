package org.example.simple.rpc.client;

import java.util.concurrent.*;
import org.example.simple.rpc.server.*;
import org.example.simple.rpc.common.*;
import org.example.simple.rpc.config.RpcConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RpcClientTest {
    interface Service { String waitForResult(); }
    @Test void asyncCallTimesOutWithoutBlockingGetAndCleansPendingMap() throws Exception {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register("服务", Service.class, (Service) () -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "迟到";
        });
        RpcConfig config = RpcConfig.builder().requestTimeoutMillis(50).drainTimeoutMillis(100).build();
        try (RpcServer server = new RpcServer(registry, config); RpcClient client = new RpcClient(config)) {
            server.start("127.0.0.1", 0); client.connect("127.0.0.1", server.getPort());
            var call = client.invokeAsync("服务", "waitForResult", String.class, new Class<?>[0], new Object[0]);
            CountDownLatch completed = new CountDownLatch(1);
            call.whenComplete((value, error) -> completed.countDown());
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(RpcErrorCode.TIMEOUT, ((RpcException) assertThrows(CompletionException.class,
                call::join).getCause()).getErrorCode());
            assertEquals(0, client.pendingCount());
            var cancelled = client.invokeAsync("服务", "waitForResult", String.class, new Class<?>[0], new Object[0]);
            cancelled.cancel(false); assertEquals(0, client.pendingCount());
        }
    }
    @Test void validatesManualThreadConfiguration() {
        RpcConfig defaults = RpcConfig.defaults();
        assertEquals(1, defaults.serverBossThreads()); assertEquals(4, defaults.serverWorkerThreads());
        assertEquals(2, defaults.clientIoThreads()); assertEquals(8, defaults.businessThreads());
        assertThrows(IllegalArgumentException.class, () -> RpcConfig.builder().clientIoThreads(0).build());
        assertThrows(IllegalArgumentException.class, () -> RpcConfig.builder().serverWorkerThreads(-1).build());
        assertEquals(3, RpcConfig.builder().serverWorkerThreads(3).build().serverWorkerThreads());
    }
}
