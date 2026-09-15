package org.example.simple.rpc.loadbalance;

import io.netty.channel.Channel;

import org.example.simple.rpc.discovery.ServiceInstance;

import java.util.List;

/** 策略只从调用方已经过滤的连接快照中选择，不执行网络操作。 */
public interface LoadBalancer {
    /** 已建立连接且可被选择的服务实例。 */
    record Candidate(ServiceInstance instance, Channel channel) {}

    /**
     * 从已过滤的候选连接中选择一个实例。
     *
     * @param candidates 可用候选连接快照
     * @param routingKey 可选路由键
     * @return 被选中的候选；无可用候选时返回 {@code null}
     */
    Candidate select(List<Candidate> candidates, String routingKey);
}
