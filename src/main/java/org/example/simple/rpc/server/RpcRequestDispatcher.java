package org.example.simple.rpc.server;

import org.example.simple.rpc.common.*;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

/**
 * 只按本地注册签名调用服务，参数与结果编码可插拔。
 */
public final class RpcRequestDispatcher {
    /** 本地服务注册表，只按已注册签名定位方法。 */
    private final ServiceRegistry registry;

    /** 本次请求所用序列化器，由请求头中的序列化标识决定。 */
    private final MessageSerializer serializer;

    /**
     * 创建请求分发器。
     *
     * @param registry 本地服务注册表
     * @param serializer 与请求头序列化标识匹配的序列化器
     */
    public RpcRequestDispatcher(ServiceRegistry registry, MessageSerializer serializer) {
        this.registry = registry;
        this.serializer = serializer;
    }

    /**
     * 执行一次请求分发：定位方法、解码参数、反射调用、编码返回值。
     *
     * <p>任何一步失败都转换为结构化失败响应而不是抛出异常，并按失败阶段返回不同错误类型，
     * 便于排查问题落在哪一环：
     *
     * <ul>
     *   <li>服务未注册返回 {@link RpcErrorCode#SERVICE_NOT_FOUND}，方法签名不匹配返回
     *       {@link RpcErrorCode#METHOD_NOT_FOUND}
     *   <li>参数类型校验或解码失败返回 {@link RpcErrorCode#INVALID_REQUEST}
     *   <li>业务方法本身抛异常返回 {@link RpcErrorCode#INVOCATION_FAILED}，并在服务端日志中
     *       记录原始异常（反射异常会解包出真实原因），对外不泄漏内部堆栈
     *   <li>返回值编码失败返回 {@link RpcErrorCode#SERIALIZATION_FAILED}
     * </ul>
     *
     * @param request 已解码的请求信封
     * @return 成功或失败的响应信封，不抛出异常
     */
    public RpcResponse dispatch(RpcRequest request) {
        var invocation =
            registry.findInvocation(
                request.serviceName(), request.methodName(), request.parameterTypeNames());
        if (invocation == null) {
            return RpcResponse.failure(
                registry.containsService(request.serviceName())
                    ? RpcErrorCode.METHOD_NOT_FOUND
                    : RpcErrorCode.SERVICE_NOT_FOUND,
                "未找到注册服务方法");
        }
        Object[] arguments = new Object[request.arguments().size()];
        try {
            serializer.validateType(invocation.method().getGenericReturnType());
            var types = invocation.method().getGenericParameterTypes();
            for (var type : types) {
                serializer.validateType(type);
            }
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = request.arguments().get(i).decode(types[i], serializer);
            }
        } catch (RuntimeException error) {
            return RpcResponse.failure(RpcErrorCode.INVALID_REQUEST, "请求参数不匹配");
        }
        Object result;
        try {
            result = invocation.method().invoke(invocation.implementation(), arguments);
        } catch (ReflectiveOperationException | RuntimeException error) {
            LoggerFactory.getLogger(getClass())
                .error(
                    "RPC 业务执行失败",
                    error instanceof InvocationTargetException e ? e.getCause() : error);
            return RpcResponse.failure(RpcErrorCode.INVOCATION_FAILED, "服务方法执行失败");
        }
        try {
            return RpcResponse.success(
                RpcPayload.of(result, invocation.method().getGenericReturnType(), serializer));
        } catch (RuntimeException error) {
            return RpcResponse.failure(RpcErrorCode.SERIALIZATION_FAILED, "返回值编码失败");
        }
    }
}
