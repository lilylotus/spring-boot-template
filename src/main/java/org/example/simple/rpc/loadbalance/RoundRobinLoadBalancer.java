package org.example.simple.rpc.loadbalance;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 无符号溢出安全的轮询。 */
public final class RoundRobinLoadBalancer implements LoadBalancer {
    private final AtomicLong sequence = new AtomicLong();
    public Candidate select(List<Candidate> candidates, String key) {
        if (candidates.isEmpty()) { throw new IllegalStateException("没有可用服务实例"); }
        return candidates.get((int) Long.remainderUnsigned(sequence.getAndIncrement(), candidates.size()));
    }
}
