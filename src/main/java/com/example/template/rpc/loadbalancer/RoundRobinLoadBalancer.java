package com.example.template.rpc.loadbalancer;

import com.example.template.rpc.RpcException;
import com.example.template.rpc.protocol.RpcRequest;
import io.netty.channel.Channel;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡：按顺序依次选择可用连接列表中的下一个，是默认的负载均衡策略。
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

    /** 轮询游标，每次选择后自增；与 channels.size() 取模得到实际下标。 */
    private final AtomicInteger cursor = new AtomicInteger(0);

    @Override
    public Channel select(List<Channel> channels, RpcRequest request) {
        if (channels == null || channels.isEmpty()) {
            throw new RpcException("没有可用的服务端连接");
        }
        // getAndIncrement可能自增到Integer溢出变负数，取模前先按位清掉符号位，避免负数下标
        int index = (cursor.getAndIncrement() & Integer.MAX_VALUE) % channels.size();
        return channels.get(index);
    }

}
