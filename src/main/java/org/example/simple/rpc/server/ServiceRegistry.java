package org.example.simple.rpc.server;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 显式注册 RPC 服务及其允许远程调用的接口方法。 */
public final class ServiceRegistry {

    /** 服务名到已注册服务的映射，启动期写入、运行期只读，因此可无锁并发查找。 */
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

        Map<MethodSignature, Method> methods =
                Arrays.stream(serviceInterface.getMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(ServiceRegistry::prepareMethod)
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        MethodSignature::from, method -> method));
        RegisteredService service = new RegisteredService(implementation, methods);
        if (services.putIfAbsent(serviceName, service) != null) {
            throw new IllegalArgumentException("服务名已注册: " + serviceName);
        }
    }

    /**
     * 按服务名与方法签名定位可调用的方法。
     *
     * <p>只在注册表中按名称与参数类型名精确匹配，从不根据请求内容加载类或反射搜索方法，
     * 避免远端指定任意方法或类型。
     *
     * @param serviceName 服务名
     * @param methodName 方法名
     * @param parameterTypeNames 参数声明类型名列表，用于区分重载方法
     * @return 定位到的调用目标；服务或方法未注册时返回 {@code null}
     */
    RegisteredInvocation findInvocation(
            String serviceName, String methodName, List<String> parameterTypeNames) {
        RegisteredService service = services.get(serviceName);
        if (service == null) {
            return null;
        }
        Method method =
                service.methods()
                        .get(new MethodSignature(methodName, List.copyOf(parameterTypeNames)));
        if (method == null) {
            return null;
        }
        return new RegisteredInvocation(service.implementation(), method);
    }

    /**
     * 判断服务名是否已注册。
     *
     * @param serviceName 服务名
     * @return 已注册时为 {@code true}；用于区分"服务未注册"与"方法未注册"两种错误
     */
    boolean containsService(String serviceName) {
        return services.containsKey(serviceName);
    }

    /**
     * 校验字符串参数非空白。
     *
     * @param value 待校验的值
     * @param fieldName 字段名，用于异常信息
     * @throws IllegalArgumentException 值为空或全空白时抛出
     */
    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
    }

    /**
     * 在注册阶段就打开方法的反射访问权限。
     *
     * <p>提前失败：把访问权限问题暴露在启动期，而不是等到线上第一次调用才失败。
     *
     * @param method 服务接口方法
     * @return 已可访问的方法
     * @throws IllegalArgumentException 方法无法访问时抛出
     */
    private static Method prepareMethod(Method method) {
        if (!method.trySetAccessible()) {
            throw new IllegalArgumentException("服务接口方法无法访问: " + method.getName());
        }
        return method;
    }

    /**
     * 已注册的服务实例及其允许调用的方法。
     *
     * @param implementation 服务实现实例
     * @param methods 方法签名到接口方法的不可变映射
     */
    private record RegisteredService(Object implementation, Map<MethodSignature, Method> methods) {}

    /**
     * 用于精确匹配重载方法的签名。
     *
     * @param methodName 方法名
     * @param parameterTypeNames 参数类型全限定名列表，顺序敏感
     */
    private record MethodSignature(String methodName, List<String> parameterTypeNames) {

        /**
         * 从反射方法提取签名。
         *
         * @param method 服务接口方法
         * @return 对应的方法签名
         */
        private static MethodSignature from(Method method) {
            List<String> typeNames =
                    Arrays.stream(method.getParameterTypes()).map(Class::getName).toList();
            return new MethodSignature(method.getName(), typeNames);
        }
    }

    /**
     * 已定位的服务实例和接口方法。
     *
     * @param implementation 服务实现实例
     * @param method 待反射调用的接口方法
     */
    record RegisteredInvocation(Object implementation, Method method) {}
}
