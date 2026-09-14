package org.example.simple.rpc.server;

import java.util.*;
import org.example.simple.rpc.common.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RpcRequestDispatcherTest {
    interface Service { int add(int value); String fail(); }
    static class Implementation implements Service {
        public int add(int value) { return value + 1; }
        public String fail() { throw new IllegalStateException("内部错误"); }
        public String secret() { return "不暴露"; }
    }
    @Test void dispatchesOnlyRegisteredMethodsForBothEncodings() {
        ServiceRegistry registry = new ServiceRegistry(); registry.register("服务", Service.class, new Implementation());
        for (MessageSerializer serializer : new MessageSerializer[]{new JacksonJsonSerializer(), new ProtostuffSerializer()}) {
            RpcRequestDispatcher dispatcher = new RpcRequestDispatcher(registry, serializer);
            RpcRequest request = new RpcRequest("服务", "add", List.of("int"),
                List.of(RpcPayload.of(4, int.class, serializer)), 5000, Map.of());
            assertEquals(5, dispatcher.dispatch(request).result().decode(int.class, serializer));
            assertEquals(RpcErrorCode.METHOD_NOT_FOUND, dispatcher.dispatch(new RpcRequest("服务", "secret",
                List.of(), List.of(), 5000, Map.of())).error().code());
            assertEquals(RpcErrorCode.SERVICE_NOT_FOUND, dispatcher.dispatch(new RpcRequest("未知", "add",
                List.of(), List.of(), 5000, Map.of())).error().code());
            assertEquals("服务方法执行失败", dispatcher.dispatch(new RpcRequest("服务", "fail",
                List.of(), List.of(), 5000, Map.of())).error().message());
        }
    }
}
