package com.example.template.rpc.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * “接口全限定名 -&gt; 服务实现实例”的注册表，使用方在服务端启动前显式调用
 * {@link #register} 完成注册，不做类路径扫描/注解自动发现，保持简单可预期。
 */
public class ServiceRegistry {

    private final Map<String, Object> services = new ConcurrentHashMap<>();

    /**
     * 注册一个服务实现。
     *
     * @param interfaceClass 服务接口
     * @param implementation 实现实例
     */
    public void register(Class<?> interfaceClass, Object implementation) {
        services.put(interfaceClass.getName(), implementation);
    }

    /**
     * 按接口全限定名查找已注册的实现实例。
     *
     * @param interfaceName 接口全限定名
     * @return 实现实例；未注册时返回 {@code null}
     */
    public Object lookup(String interfaceName) {
        return services.get(interfaceName);
    }

}
