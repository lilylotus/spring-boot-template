package org.example.simple.rpc.client;

import java.util.concurrent.*;
import org.example.simple.rpc.server.*;
import org.example.simple.rpc.config.RpcConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RpcClientHandlerTest {
    interface Service { String echo(String value); }
    @Test void outOfOrderMixedEncodingResponsesMatchRequests() throws Exception {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register("服务", Service.class, (Service) value -> {
            if (value.equals("慢")) { try { Thread.sleep(100); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            return value;
        });
        try (RpcServer server = new RpcServer(registry); RpcClient client = new RpcClient()) {
            server.start("127.0.0.1", 0); client.connect("127.0.0.1", server.getPort());
            CompletableFuture<String> slow = client.invokeAsync("服务", "echo", String.class,
                new Class<?>[]{String.class}, new Object[]{"慢"}, new CallOptions((byte) 2, 5000, null, false, 0));
            CompletableFuture<String> fast = client.invokeAsync("服务", "echo", String.class,
                new Class<?>[]{String.class}, new Object[]{"快"}, new CallOptions((byte) 1, 5000, null, false, 0));
            assertEquals("快", fast.get(2, TimeUnit.SECONDS)); assertEquals("慢", slow.get(2, TimeUnit.SECONDS));
            assertEquals(0, client.pendingCount());
        }
    }
}
