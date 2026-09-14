package org.example.simple.rpc.loadbalance;

import java.util.List;
import io.netty.channel.Channel;
import org.example.simple.rpc.discovery.ServiceInstance;

/** 策略只从调用方已经过滤的连接快照中选择，不执行网络操作。 */
public interface LoadBalancer {
    record Candidate(ServiceInstance instance, Channel channel) { }
    Candidate select(List<Candidate> candidates, String routingKey);
}
