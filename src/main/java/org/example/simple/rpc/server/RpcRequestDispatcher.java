package org.example.simple.rpc.server;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import org.example.simple.rpc.common.MessageSerializer;
import org.example.simple.rpc.common.RpcErrorCode;
import org.example.simple.rpc.common.RpcException;
import org.example.simple.rpc.common.RpcRequest;
import org.example.simple.rpc.common.RpcResponse;

/**
 * 将 RPC 请求分发到已注册服务，并将结果转换为响应。
 */
public final class RpcRequestDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(RpcRequestDispatcher.class);

    private final ServiceRegistry serviceRegistry;
    private final MessageSerializer serializer;

    /**
     * 创建请求分发器。
     *
     * @param serviceRegistry 服务注册表
     * @param serializer 参数与结果转换器
     */
    public RpcRequestDispatcher(ServiceRegistry serviceRegistry, MessageSerializer serializer) {
        this.serviceRegistry = Objects.requireNonNull(serviceRegistry, "服务注册表不能为空");
        this.serializer = Objects.requireNonNull(serializer, "消息序列化器不能为空");
    }

    /**
     * 同步处理一个 RPC 请求。
     *
     * @param request RPC 请求
     * @return 成功或失败响应
     */
    public RpcResponse dispatch(RpcRequest request) {
        Objects.requireNonNull(request, "RPC 请求不能为空");
        ServiceRegistry.RegisteredInvocation invocation = serviceRegistry.findInvocation(
            request.serviceName(),
            request.methodName(),
            request.parameterTypeNames());
        if (invocation == null) {
            return missingTargetResponse(request);
        }

        Object[] arguments;
        try {
            arguments = convertArguments(request.arguments(), invocation.method());
        } catch (RpcException | IllegalArgumentException exception) {
            LOG.warn("RPC 请求参数转换失败，服务={}，方法={}", request.serviceName(), request.methodName(), exception);
            return RpcResponse.failure(request.requestId(), RpcErrorCode.INVALID_REQUEST, "请求参数不匹配");
        }

        Object result;
        try {
            result = invocation.method().invoke(invocation.implementation(), arguments);
        } catch (IllegalAccessException exception) {
            LOG.error("RPC 服务方法不可访问，服务={}，方法={}", request.serviceName(), request.methodName(), exception);
            return RpcResponse.failure(request.requestId(), RpcErrorCode.INVOCATION_FAILED, "服务方法不可访问");
        } catch (InvocationTargetException exception) {
            LOG.error("RPC 业务方法执行失败，服务={}，方法={}", request.serviceName(), request.methodName(),
                exception.getTargetException());
            return RpcResponse.failure(request.requestId(), RpcErrorCode.INVOCATION_FAILED, "服务方法执行失败");
        } catch (IllegalArgumentException exception) {
            LOG.warn("RPC 请求参数与服务方法不匹配，服务={}，方法={}", request.serviceName(), request.methodName(), exception);
            return RpcResponse.failure(request.requestId(), RpcErrorCode.INVALID_REQUEST, "请求参数不匹配");
        } catch (RuntimeException exception) {
            LOG.error("RPC 请求处理发生未预期错误，服务={}，方法={}", request.serviceName(), request.methodName(), exception);
            return RpcResponse.failure(request.requestId(), RpcErrorCode.INVOCATION_FAILED, "服务调用失败");
        }

        try {
            String resultJson = new String(serializer.serialize(result), StandardCharsets.UTF_8);
            return RpcResponse.success(request.requestId(), resultJson);
        } catch (RpcException exception) {
            LOG.error("RPC 返回值序列化失败，服务={}，方法={}", request.serviceName(), request.methodName(), exception);
            return RpcResponse.failure(request.requestId(), RpcErrorCode.SERIALIZATION_FAILED, "服务返回值序列化失败");
        }
    }

    private RpcResponse missingTargetResponse(RpcRequest request) {
        RpcErrorCode code = serviceRegistry.containsService(request.serviceName())
            ? RpcErrorCode.METHOD_NOT_FOUND
            : RpcErrorCode.SERVICE_NOT_FOUND;
        return RpcResponse.failure(request.requestId(), code, "未找到匹配的 RPC 服务方法");
    }

    private Object[] convertArguments(List<JsonNode> argumentNodes, Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        for (int index = 0; index < parameterTypes.length; index++) {
            arguments[index] = serializer.fromTree(argumentNodes.get(index), parameterTypes[index]);
        }
        return arguments;
    }
}
