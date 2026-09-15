package org.example.simple.rpc.discovery;

/** 注册中心提供的稳定实例标识和路由信息。 */
public record ServiceInstance(String id, String host, int port, double weight) {
    public ServiceInstance {
        if (id == null
                || id.isBlank()
                || host == null
                || host.isBlank()
                || port <= 0
                || port > 65535
                || !Double.isFinite(weight)) {
            throw new IllegalArgumentException("服务实例参数不合法");
        }
    }
}
