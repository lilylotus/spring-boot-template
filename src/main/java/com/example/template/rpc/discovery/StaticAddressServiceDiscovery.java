package com.example.template.rpc.discovery;

import com.example.template.rpc.RpcException;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于启动时配置的静态地址列表维护连接的默认服务发现实现，不接入任何真实注册中心。
 * 如需接入 Nacos/ZooKeeper，实现 {@link ServiceDiscovery} 并在服务实例变化时调用
 * {@link #onAddressesChanged}(或参照本类写法自行实现) 即可，不需要改动客户端其它逻辑。
 */
public class StaticAddressServiceDiscovery implements ServiceDiscovery {

    private static final Logger log = LoggerFactory.getLogger(StaticAddressServiceDiscovery.class);

    private final Bootstrap bootstrap;

    /** 当前可用连接列表；用CopyOnWriteArrayList是因为读(负载均衡选择)远比写(上下线变更)频繁。 */
    private final CopyOnWriteArrayList<Channel> channels = new CopyOnWriteArrayList<>();

    public StaticAddressServiceDiscovery(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void start(List<InetSocketAddress> addresses) {
        onAddressesChanged(addresses);
    }

    @Override
    public List<Channel> getChannels() {
        return channels;
    }

    @Override
    public void onAddressesChanged(List<InetSocketAddress> addresses) {
        Set<InetSocketAddress> desired = new HashSet<>(addresses);

        // 关闭已不在目标地址列表中的连接(下线)
        for (Channel channel : channels) {
            if (!desired.contains(channel.remoteAddress())) {
                channels.remove(channel);
                channel.close();
            }
        }

        // 为目标地址列表中尚未建立连接的地址新建连接(上线)
        Set<InetSocketAddress> connected = new HashSet<>();
        for (Channel channel : channels) {
            connected.add((InetSocketAddress) channel.remoteAddress());
        }
        for (InetSocketAddress address : addresses) {
            if (!connected.contains(address)) {
                connect(address);
            }
        }
    }

    private void connect(InetSocketAddress address) {
        try {
            ChannelFuture future = bootstrap.connect(address).sync();
            Channel channel = future.channel();
            channels.add(channel);
            // 连接意外断开(网络问题/对端重启)时把它从可用列表摘除，避免负载均衡选到一个已失效的连接
            channel.closeFuture().addListener(f -> {
                channels.remove(channel);
                log.warn("RPC客户端与服务端连接已断开: {}", address);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException("连接RPC服务端失败: " + address, e);
        }
    }

    @Override
    public void shutdown() {
        for (Channel channel : channels) {
            channel.close();
        }
        channels.clear();
    }

}
