package org.example.simple.rpc.common;

import java.util.List;
import java.util.Map;

/** 与编码格式无关的请求信封；请求编号仅存储在协议头。 */
public record RpcRequest(String serviceName, String methodName, List<String> parameterTypeNames,
                         List<RpcPayload> arguments, long timeoutMillis, Map<String, String> traceContext) {
    public RpcRequest {
        if (serviceName == null || serviceName.isBlank() || methodName == null || methodName.isBlank()
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
