package com.example.template.rpc.discovery;

import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 维护客户端可用连接列表的服务发现接口。生产环境应该接入 Nacos/ZooKeeper 等注册中心，订阅服务
 * 实例上下线事件动态调用 {@link #onAddressesChanged}；本仓库默认只提供
 * {@link StaticAddressServiceDiscovery} 这个基于静态地址列表的实现，接口本身按可扩展的方式设计。
 */
public interface ServiceDiscovery {

    /**
     * 启动服务发现，与初始地址列表建立连接。
     *
     * @param addresses 初始服务端地址列表
     */
    void start(List<InetSocketAddress> addresses);

    /**
     * 返回当前可用于负载均衡选择的连接列表。
     *
     * @return 可用连接列表(实时视图，调用方不应长期持有)
     */
    List<Channel> getChannels();

    /**
     * 服务实例列表发生变化时的回调：建立新增地址的连接、关闭已下线地址对应的连接，并更新
     * {@link #getChannels()} 返回的列表。
     *
     * @param addresses 变化后的完整地址列表
     */
    void onAddressesChanged(List<InetSocketAddress> addresses);

    /**
     * 关闭所有连接并释放资源，在客户端停止时调用。
     */
    void shutdown();

}
