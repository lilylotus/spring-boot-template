package org.example.simple.rpc.common;

import java.util.List;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

/**
 * RPC 请求消息。
 *
 * @param requestId 请求唯一标识
 * @param serviceName 注册服务名
 * @param methodName 调用方法名
 * @param parameterTypeNames 参数类型全限定名
 * @param arguments JSON 参数值
 */
public record RpcRequest(
    String requestId,
    String serviceName,
    String methodName,
    List<String> parameterTypeNames,
    List<JsonNode> arguments) {

    public RpcRequest {
        requireText(requestId, "请求标识");
        requireText(serviceName, "服务名");
        requireText(methodName, "方法名");
        parameterTypeNames = List.copyOf(Objects.requireNonNull(parameterTypeNames, "参数类型不能为空"));
        arguments = List.copyOf(Objects.requireNonNull(arguments, "参数值不能为空"));
        if (parameterTypeNames.size() != arguments.size()) {
            throw new IllegalArgumentException("参数类型数量必须与参数值数量一致");
        }
        for (String parameterTypeName : parameterTypeNames) {
            requireText(parameterTypeName, "参数类型名");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
    }
}
