package org.example.simple.rpc.loadbalance;

import java.util.*;

/** 平滑加权轮询，动态快照中已移除节点的状态及时清理。 */
public final class WeightedRoundRobinLoadBalancer implements LoadBalancer {
    private final Map<String, Double> scores = new HashMap<>();
    public synchronized Candidate select(List<Candidate> candidates, String key) {
        Set<String> ids = new HashSet<>();
        Candidate winner = null;
        double best = -Double.MAX_VALUE;
        double total = 0;
        for (Candidate candidate : candidates) {
            double weight = candidate.instance().weight();
            if (weight <= 0) { continue; }
            String id = candidate.instance().id(); ids.add(id); total += weight;
            double score = scores.merge(id, weight, Double::sum);
            if (score > best) { best = score; winner = candidate; }
        }
        scores.keySet().retainAll(ids);
        if (winner == null) { throw new IllegalStateException("没有正权重服务实例"); }
        scores.put(winner.instance().id(), best - total);
        return winner;
    }
}
