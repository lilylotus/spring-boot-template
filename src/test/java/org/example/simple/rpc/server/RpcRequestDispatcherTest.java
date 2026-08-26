package org.example.simple.rpc.server;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.example.simple.rpc.common.JacksonJsonSerializer;
import org.example.simple.rpc.common.RpcErrorCode;
import org.example.simple.rpc.common.RpcRequest;
import org.example.simple.rpc.common.RpcResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RPC 请求分发器测试。
 */
class RpcRequestDispatcherTest {

    private static final String SERVICE_NAME = "回显服务";

    private final JacksonJsonSerializer serializer = new JacksonJsonSerializer();
    private RpcRequestDispatcher dispatcher;

    @BeforeEach
    void initializeDispatcher() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, EchoService.class, new EchoServiceImpl());
        dispatcher = new RpcRequestDispatcher(registry, serializer);
    }

    @Test
    void registeredServiceReturnsSuccess() {
        RpcRequest request = request("请求-成功", "echo", String.class, "你好");

        RpcResponse response = dispatcher.dispatch(request);

        assertTrue(response.success());
        assertEquals("\"字符串:你好\"", response.result());
        assertEquals("字符串:你好", deserializeResult(response, String.class));
    }

    @Test
    void parameterTypesSelectOverloadedMethod() {
        RpcRequest request = request("请求-重载", "echo", int.class, 8);

        RpcResponse response = dispatcher.dispatch(request);

        assertTrue(response.success());
        assertEquals("整数:8", deserializeResult(response, String.class));
    }

    @Test
    void unregisteredServiceReturnsFailure() {
        RpcRequest request = new RpcRequest("请求-未知服务", "未知服务", "echo", List.of(), List.of());

        RpcResponse response = dispatcher.dispatch(request);

        assertFalse(response.success());
        assertEquals(RpcErrorCode.SERVICE_NOT_FOUND, response.error().code());
    }

    @Test
    void unknownMethodReturnsFailure() {
        RpcRequest request = new RpcRequest("请求-未知方法", SERVICE_NAME, "未知方法", List.of(), List.of());

        RpcResponse response = dispatcher.dispatch(request);

        assertFalse(response.success());
        assertEquals(RpcErrorCode.METHOD_NOT_FOUND, response.error().code());
    }

    @Test
    void conversionFailureReturnsInvalidRequest() {
        RpcRequest request = request("请求-错误参数", "echo", int.class, "不是数字");

        RpcResponse response = dispatcher.dispatch(request);

        assertFalse(response.success());
        assertEquals(RpcErrorCode.INVALID_REQUEST, response.error().code());
    }

    @Test
    void businessFailureDoesNotAffectNextInvocation() {
        RpcRequest failedRequest = new RpcRequest("请求-异常", SERVICE_NAME, "fail", List.of(), List.of());
        RpcRequest nextRequest = request("请求-后续", "echo", String.class, "继续");

        RpcResponse failedResponse = dispatcher.dispatch(failedRequest);
        RpcResponse nextResponse = dispatcher.dispatch(nextRequest);

        assertFalse(failedResponse.success());
        assertEquals(RpcErrorCode.INVOCATION_FAILED, failedResponse.error().code());
        assertTrue(nextResponse.success());
    }

    @Test
    void serializationFailureReturnsStructuredError() {
        RpcRequest request = new RpcRequest(
            "请求-序列化失败",
            SERVICE_NAME,
            "getCyclicResult",
            List.of(),
            List.of());

        RpcResponse response = dispatcher.dispatch(request);

        assertFalse(response.success());
        assertEquals(RpcErrorCode.SERIALIZATION_FAILED, response.error().code());
    }

    private RpcRequest request(String requestId, String methodName, Class<?> parameterType, Object argument) {
        return new RpcRequest(
            requestId,
            SERVICE_NAME,
            methodName,
            List.of(parameterType.getName()),
            List.of(serializer.toTree(argument)));
    }

    private <T> T deserializeResult(RpcResponse response, Class<T> resultType) {
        return serializer.deserialize(response.result().getBytes(StandardCharsets.UTF_8), resultType);
    }

    /**
     * 测试使用的重载回显服务。
     */
    interface EchoService {

        /** 回显字符串参数。 */
        String echo(String value);

        /** 回显整数参数。 */
        String echo(int value);

        /** 抛出测试业务异常。 */
        String fail();

        /** 返回无法由 Jackson 序列化的循环对象。 */
        Object getCyclicResult();
    }

    /**
     * 测试回显服务实现。
     */
    static final class EchoServiceImpl implements EchoService {

        @Override
        public String echo(String value) {
            return "字符串:" + value;
        }

        @Override
        public String echo(int value) {
            return "整数:" + value;
        }

        @Override
        public String fail() {
            throw new IllegalStateException("预期的业务异常");
        }

        @Override
        public Object getCyclicResult() {
            return new CyclicResult();
        }
    }

    /**
     * 通过直接自引用触发 Jackson 序列化失败的测试对象。
     */
    static final class CyclicResult {

        /**
         * 返回对象自身以形成无法序列化的直接循环。
         *
         * @return 当前对象
         */
        public CyclicResult getSelf() {
            return this;
        }
    }
}
