package org.example.simple.rpc.loadbalance;

import java.util.*;

/** 平滑加权轮询，动态快照中已移除节点的状态及时清理。 */
public final class WeightedRoundRobinLoadBalancer implements LoadBalancer {
    /** 实例标识到当前累计权重的映射，选中后扣减总权重，由方法级同步锁保护。 */
    private final Map<String, Double> scores = new HashMap<>();

    /**
     * 按实例权重比例平滑选择候选实例。
     *
     * <p>每次选择给每个实例累加自身权重，取累计值最大者，并从中扣减全部实例的权重之和。
     * 相比按权重连续发送同一实例的朴素做法，这种做法让高权重实例的流量均匀分散开，避免流量成串倾斜。
     *
     * <p>选择前会把已不在候选快照中的实例状态清理掉，防止实例反复上下线导致状态无限累积。
     *
     * @param candidates 可用候选连接快照
     * @param key 路由键，加权轮询策略忽略此参数
     * @return 本次选中的候选
     * @throws IllegalStateException 没有正权重候选时抛出
     */
    public synchronized Candidate select(List<Candidate> candidates, String key) {
        Set<String> ids = new HashSet<>();
        Candidate winner = null;
        double best = -Double.MAX_VALUE;
        double total = 0;
        for (Candidate candidate : candidates) {
            double weight = candidate.instance().weight();
            if (weight <= 0) {
                continue;
            }
            String id = candidate.instance().id();
            ids.add(id);
            total += weight;
            double score = scores.merge(id, weight, Double::sum);
            if (score > best) {
                best = score;
                winner = candidate;
            }
        }
        scores.keySet().retainAll(ids);
        if (winner == null) {
            throw new IllegalStateException("没有正权重服务实例");
        }
        scores.put(winner.instance().id(), best - total);
        return winner;
    }
}
