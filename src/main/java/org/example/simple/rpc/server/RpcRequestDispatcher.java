package org.example.simple.rpc.server;

import java.lang.reflect.InvocationTargetException;
import org.example.simple.rpc.common.*;
import org.slf4j.LoggerFactory;

/** 只按本地注册签名调用服务，参数与结果编码可插拔。 */
public final class RpcRequestDispatcher {
    private final ServiceRegistry registry;
    private final MessageSerializer serializer;
    public RpcRequestDispatcher(ServiceRegistry registry, MessageSerializer serializer) {
        this.registry = registry; this.serializer = serializer;
    }
    public RpcResponse dispatch(RpcRequest request) {
        var invocation = registry.findInvocation(request.serviceName(), request.methodName(), request.parameterTypeNames());
        if (invocation == null) {
            return RpcResponse.failure(registry.containsService(request.serviceName())
                ? RpcErrorCode.METHOD_NOT_FOUND : RpcErrorCode.SERVICE_NOT_FOUND, "未找到注册服务方法");
        }
        Object[] arguments = new Object[request.arguments().size()];
        try {
            serializer.validateType(invocation.method().getGenericReturnType());
            var types = invocation.method().getGenericParameterTypes();
            for (var type : types) { serializer.validateType(type); }
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = request.arguments().get(i).decode(types[i], serializer);
            }
        } catch (RuntimeException error) {
            return RpcResponse.failure(RpcErrorCode.INVALID_REQUEST, "请求参数不匹配");
        }
        Object result;
        try { result = invocation.method().invoke(invocation.implementation(), arguments); }
        catch (ReflectiveOperationException | RuntimeException error) {
            LoggerFactory.getLogger(getClass()).error("RPC 业务执行失败",
                error instanceof InvocationTargetException e ? e.getCause() : error);
            return RpcResponse.failure(RpcErrorCode.INVOCATION_FAILED, "服务方法执行失败");
        }
        try {
            return RpcResponse.success(RpcPayload.of(result, invocation.method().getGenericReturnType(), serializer));
        } catch (RuntimeException error) {
            return RpcResponse.failure(RpcErrorCode.SERIALIZATION_FAILED, "返回值编码失败");
        }
    }
}
