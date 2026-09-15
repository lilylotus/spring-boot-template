package com.example.template.rpc.loadbalancer;

import com.example.template.rpc.RpcException;
import com.example.template.rpc.protocol.RpcRequest;
import io.netty.channel.Channel;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 轮询负载均衡器的离线单元测试。
 */
class RoundRobinLoadBalancerTest {

    @Test
    void shouldCycleThroughChannelsInOrder() {
        RoundRobinLoadBalancer loadBalancer = new RoundRobinLoadBalancer();
        Channel channelA = mock(Channel.class);
        Channel channelB = mock(Channel.class);
        Channel channelC = mock(Channel.class);
        List<Channel> channels = List.of(channelA, channelB, channelC);
        RpcRequest request = new RpcRequest();

        assertEquals(channelA, loadBalancer.select(channels, request));
        assertEquals(channelB, loadBalancer.select(channels, request));
        assertEquals(channelC, loadBalancer.select(channels, request));
        // 第四次选择应该回到列表开头，验证是"轮询"而不是每次都选同一个
        assertEquals(channelA, loadBalancer.select(channels, request));
    }

    @Test
    void shouldThrowWhenNoChannelAvailable() {
        RoundRobinLoadBalancer loadBalancer = new RoundRobinLoadBalancer();
        assertThrows(RpcException.class, () -> loadBalancer.select(Collections.emptyList(), new RpcRequest()));
    }

}
