package com.example.template.rpc.loadbalancer;

import com.example.template.rpc.protocol.RpcRequest;
import io.netty.channel.Channel;

import java.util.List;

/**
 * 客户端负载均衡策略：从当前可用连接列表中选出一个用于承载本次请求的 {@link Channel}。
 * 可用连接列表由 {@code ServiceDiscovery} 动态维护，本接口只负责“选哪个”，不负责“列表从哪来”。
 */
public interface LoadBalancer {

    /**
     * 从可用连接中选择一个。
     *
     * @param channels 当前可用的连接列表
     * @param request  本次待发送的请求(供一致性哈希等按请求内容路由的策略使用)
     * @return 选中的连接
     */
    Channel select(List<Channel> channels, RpcRequest request);

}
