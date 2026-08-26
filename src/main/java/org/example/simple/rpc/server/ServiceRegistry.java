package org.example.simple.rpc.server;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 显式注册 RPC 服务及其允许远程调用的接口方法。
 */
public final class ServiceRegistry {

    private final Map<String, RegisteredService> services = new ConcurrentHashMap<>();

    /**
     * 注册一个服务接口和对应实现。
     *
     * @param serviceName 对外服务名
     * @param serviceInterface 允许远程调用的服务接口
     * @param implementation 服务实现实例
     */
    public void register(String serviceName, Class<?> serviceInterface, Object implementation) {
        requireText(serviceName, "服务名");
        Objects.requireNonNull(serviceInterface, "服务接口不能为空");
        Objects.requireNonNull(implementation, "服务实现不能为空");
        if (!serviceInterface.isInterface()) {
            throw new IllegalArgumentException("服务类型必须是接口");
        }
        if (!serviceInterface.isInstance(implementation)) {
            throw new IllegalArgumentException("服务实现必须实现指定接口");
        }

        Map<MethodSignature, Method> methods = Arrays.stream(serviceInterface.getMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(ServiceRegistry::prepareMethod)
            .collect(Collectors.toUnmodifiableMap(MethodSignature::from, method -> method));
        RegisteredService service = new RegisteredService(implementation, methods);
        if (services.putIfAbsent(serviceName, service) != null) {
            throw new IllegalArgumentException("服务名已注册: " + serviceName);
        }
    }

    RegisteredInvocation findInvocation(
        String serviceName,
        String methodName,
        List<String> parameterTypeNames) {
        RegisteredService service = services.get(serviceName);
        if (service == null) {
            return null;
        }
        Method method = service.methods().get(new MethodSignature(methodName, List.copyOf(parameterTypeNames)));
        if (method == null) {
            return null;
        }
        return new RegisteredInvocation(service.implementation(), method);
    }

    boolean containsService(String serviceName) {
        return services.containsKey(serviceName);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
    }

    private static Method prepareMethod(Method method) {
        if (!method.trySetAccessible()) {
            throw new IllegalArgumentException("服务接口方法无法访问: " + method.getName());
        }
        return method;
    }

    /** 已注册的服务实例及其允许调用的方法。 */
    private record RegisteredService(Object implementation, Map<MethodSignature, Method> methods) {
    }

    /** 用于精确匹配重载方法的签名。 */
    private record MethodSignature(String methodName, List<String> parameterTypeNames) {

        private static MethodSignature from(Method method) {
            List<String> typeNames = Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .toList();
            return new MethodSignature(method.getName(), typeNames);
        }
    }

    /** 已定位的服务实例和接口方法。 */
    record RegisteredInvocation(Object implementation, Method method) {
    }
}
