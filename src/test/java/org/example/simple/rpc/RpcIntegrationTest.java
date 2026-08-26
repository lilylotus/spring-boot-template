package org.example.simple.rpc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.example.simple.rpc.client.RpcClient;
import org.example.simple.rpc.common.RpcErrorCode;
import org.example.simple.rpc.common.RpcException;
import org.example.simple.rpc.server.RpcServer;
import org.example.simple.rpc.server.ServiceRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Netty JSON RPC 客户端与服务端全链路测试。
 */
class RpcIntegrationTest {

    private static final String SERVICE_NAME = "问候服务";

    private RpcServer server;
    private RpcClient client;

    @BeforeEach
    void startRpcTransport() throws InterruptedException {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, GreetingService.class, new GreetingServiceImpl());
        server = new RpcServer(registry);
        server.start("127.0.0.1", 0);
        client = new RpcClient();
        client.connect("127.0.0.1", server.getPort());
    }

    @AfterEach
    void closeRpcTransport() {
        client.close();
        server.close();
    }

    @Test
    void clientInvokesOverloadedServerMethods() {
        String textResult = client.invoke(
            SERVICE_NAME,
            "greet",
            String.class,
            new Class<?>[] {String.class},
            new Object[] {"小明"});
        String countResult = client.invoke(
            SERVICE_NAME,
            "greet",
            String.class,
            new Class<?>[] {int.class},
            new Object[] {2});

        assertEquals("你好，小明", textResult);
        assertEquals("你好×2", countResult);
    }

    @Test
    void businessExceptionIsSafelyTransferredAsRpcException() {
        RpcException exception = assertThrows(
            RpcException.class,
            () -> client.invoke(SERVICE_NAME, "fail", String.class, new Class<?>[0], new Object[0]));

        assertEquals(RpcErrorCode.INVOCATION_FAILED, exception.getErrorCode());
        assertEquals("服务方法执行失败", exception.getMessage());
    }

    @Test
    void objectAndNullResultsCompleteRoundTrip() {
        GreetingPayload payload = client.invoke(
            SERVICE_NAME,
            "getGreetingPayload",
            GreetingPayload.class,
            new Class<?>[] {String.class},
            new Object[] {"小明"});
        String nullResult = client.invoke(
            SERVICE_NAME,
            "getNullResult",
            String.class,
            new Class<?>[0],
            new Object[0]);

        assertEquals(new GreetingPayload("小明", "你好，小明"), payload);
        assertNull(nullResult);
    }

    /**
     * 测试使用的问候服务。
     */
    interface GreetingService {

        /** 根据姓名生成问候。 */
        String greet(String name);

        /** 根据次数生成问候。 */
        String greet(int count);

        /** 抛出测试业务异常。 */
        String fail();

        /** 返回用于验证对象结果的问候载荷。 */
        GreetingPayload getGreetingPayload(String name);

        /** 返回空值。 */
        String getNullResult();
    }

    /**
     * 测试问候服务实现。
     */
    static final class GreetingServiceImpl implements GreetingService {

        @Override
        public String greet(String name) {
            return "你好，" + name;
        }

        @Override
        public String greet(int count) {
            return "你好×" + count;
        }

        @Override
        public String fail() {
            throw new IllegalStateException("内部敏感异常");
        }

        @Override
        public GreetingPayload getGreetingPayload(String name) {
            return new GreetingPayload(name, "你好，" + name);
        }

        @Override
        public String getNullResult() {
            return null;
        }
    }

    /**
     * 用于验证对象结果序列化往返的问候载荷。
     *
     * @param name 姓名
     * @param message 问候内容
     */
    record GreetingPayload(String name, String message) {
    }
}
