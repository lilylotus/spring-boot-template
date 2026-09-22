package org.example.simple.rpc.loadbalance;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 无符号溢出安全的轮询。 */
public final class RoundRobinLoadBalancer implements LoadBalancer {
    /** 单调递增的轮询游标，按无符号方式取模，溢出后仍能继续均匀轮询。 */
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 按调用次序依次选择候选实例，各实例获得的流量均等。
     *
     * @param candidates 可用候选连接快照
     * @param key 路由键，轮询策略忽略此参数
     * @return 本次选中的候选
     * @throws IllegalStateException 候选为空时抛出
     */
    public Candidate select(List<Candidate> candidates, String key) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException("没有可用服务实例");
        }
        return candidates.get(
                (int) Long.remainderUnsigned(sequence.getAndIncrement(), candidates.size()));
    }
}
