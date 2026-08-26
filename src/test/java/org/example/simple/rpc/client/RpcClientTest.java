package org.example.simple.rpc.client;

import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.example.simple.rpc.common.JacksonJsonSerializer;
import org.example.simple.rpc.common.RpcErrorCode;
import org.example.simple.rpc.common.RpcException;
import org.example.simple.rpc.common.RpcProtocol;
import org.example.simple.rpc.server.RpcServer;
import org.example.simple.rpc.server.ServiceRegistry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RPC 客户端调用生命周期测试。
 */
class RpcClientTest {

    private static final String SERVICE_NAME = "延迟服务";

    private RpcServer server;
    private RpcClient client;
    private DefaultEventLoop responseEventLoop;

    @BeforeEach
    void startServer() throws InterruptedException {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, DelayService.class, new DelayServiceImpl());
        server = new RpcServer(registry);
        server.start("127.0.0.1", 0);
        responseEventLoop = new DefaultEventLoop();
        client = new RpcClient(
            new JacksonJsonSerializer(),
            RpcProtocol.DEFAULT_MAX_MESSAGE_LENGTH,
            1000,
            responseEventLoop);
        client.connect("127.0.0.1", server.getPort());
    }

    @AfterEach
    void closeResources() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void serverFailureResponseIsConvertedToRpcException() {
        RpcException exception = assertThrows(
            RpcException.class,
            () -> client.invoke(SERVICE_NAME, "fail", String.class, new Class<?>[0], new Object[0]));

        assertEquals(RpcErrorCode.INVOCATION_FAILED, exception.getErrorCode());
    }

    @Test
    void timedOutCallThrowsTimeoutException() {
        RpcException exception = assertThrows(
            RpcException.class,
            () -> client.invoke(
                SERVICE_NAME,
                "delay",
                String.class,
                new Class<?>[] {long.class},
                new Object[] {2000L}));

        assertEquals(RpcErrorCode.TIMEOUT, exception.getErrorCode());
    }

    @Test
    void clientCloseIsIdempotent() {
        client.close();

        assertDoesNotThrow(client::close);
        assertTrue(responseEventLoop.isTerminated());
    }

    @Test
    void synchronousCallIsRejectedOnResponseEventLoop() {
        Future<RpcException> result = responseEventLoop.submit(() -> assertThrows(
            RpcException.class,
            () -> client.invoke(SERVICE_NAME, "fail", String.class, new Class<?>[0], new Object[0])));

        result.syncUninterruptibly();
        assertEquals(RpcErrorCode.INVALID_REQUEST, result.getNow().getErrorCode());
    }

    /**
     * 测试使用的延迟服务。
     */
    interface DelayService {

        /** 延迟指定毫秒数后返回。 */
        String delay(long millis);

        /** 抛出测试业务异常。 */
        String fail();
    }

    /**
     * 测试延迟服务实现。
     */
    static final class DelayServiceImpl implements DelayService {

        @Override
        public String delay(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return "完成";
        }

        @Override
        public String fail() {
            throw new IllegalStateException("预期的远端异常");
        }
    }
}
