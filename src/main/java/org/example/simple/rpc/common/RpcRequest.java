package org.example.simple.rpc.common;

import java.util.List;
import java.util.Map;

/**
 * 与编码格式无关的请求信封；请求编号仅存储在协议头。
 *
 * @param serviceName 目标服务名，服务端据此在注册表中查找实现
 * @param methodName 目标方法名，与参数类型名共同确定方法签名
 * @param parameterTypeNames 参数声明类型名列表，仅用于匹配本地已注册方法，不用于加载远端类
 * @param arguments 与参数类型一一对应的参数载荷
 * @param timeoutMillis 调用方声明的超时预算，单位为毫秒，服务端据此判断请求是否已过期
 * @param traceContext 链路追踪上下文，用于跨进程串联调用链
 */
public record RpcRequest(
        String serviceName,
        String methodName,
        List<String> parameterTypeNames,
        List<RpcPayload> arguments,
        long timeoutMillis,
        Map<String, String> traceContext) {
    /**
     * 校验必填字段与超时取值，并把集合复制为不可变副本。
     *
     * @throws IllegalArgumentException 当必填字段为空、超时非正或参数类型与参数数量不一致时抛出
     */
    public RpcRequest {
        if (serviceName == null
                || serviceName.isBlank()
                || methodName == null
                || methodName.isBlank()
                || timeoutMillis <= 0) {
            throw new IllegalArgumentException("请求字段不合法");
        }
        parameterTypeNames = List.copyOf(parameterTypeNames);
        arguments = List.copyOf(arguments);
        traceContext = Map.copyOf(traceContext);
        if (parameterTypeNames.size() != arguments.size()) {
            throw new IllegalArgumentException("参数类型与参数数量不一致");
        }
    }
}
