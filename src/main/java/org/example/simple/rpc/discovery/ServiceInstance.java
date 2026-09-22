package org.example.simple.rpc.discovery;

/**
 * 注册中心提供的稳定实例标识和路由信息。
 *
 * @param id 实例唯一标识，通常为"主机:端口"，用于在连接表中定位实例
 * @param host 实例主机地址
 * @param port 实例端口
 * @param weight 实例权重，加权负载均衡按此比例分配流量，取值为 0 表示摘除该实例
 */
public record ServiceInstance(String id, String host, int port, double weight) {
    /**
     * 校验实例标识、地址、端口与权重取值。
     *
     * @throws IllegalArgumentException 当标识或地址为空、端口越界、权重非有限值时抛出
     */
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
