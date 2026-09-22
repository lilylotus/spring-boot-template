package org.example.simple.rpc.loadbalance;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 基于稳定实例标识及 128 个虚拟节点的一致性哈希。 */
public final class ConsistentHashLoadBalancer implements LoadBalancer {
    /**
     * 按路由键把请求稳定地映射到同一实例。
     *
     * <p>每个实例投放 128 个虚拟节点摊薄哈希分布不均，并按实例标识排序后建环，
     * 保证同一候选集合总能构造出完全相同的哈希环；实例增减时只有邻近区间的键会改变归属。
     * 顺时针找不到后继节点时回到环首，形成闭环。
     *
     * @param candidates 可用候选连接快照
     * @param key 路由键，必须非空
     * @return 本次选中的候选
     * @throws IllegalArgumentException 路由键为空时抛出
     * @throws IllegalStateException 候选为空时抛出
     */
    public Candidate select(List<Candidate> candidates, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("一致性哈希必须指定路由键");
        }
        TreeMap<Long, Candidate> ring = new TreeMap<>();
        candidates.stream()
                .sorted(Comparator.comparing(c -> c.instance().id()))
                .forEach(
                        candidate -> {
                            for (int i = 0; i < 128; i++) {
                                ring.put(hash(candidate.instance().id() + "#" + i), candidate);
                            }
                        });
        if (ring.isEmpty()) {
            throw new IllegalStateException("没有可用服务实例");
        }
        var entry = ring.ceilingEntry(hash(key));
        return (entry == null ? ring.firstEntry() : entry).getValue();
    }

    /**
     * 计算路由键的哈希值。
     *
     * <p>取 SHA-256 摘要的前 8 字节，保证跨进程、跨重启结果一致，避免实例间哈希环不一致。
     *
     * @param key 参与哈希的字符串
     * @return 哈希值
     * @throws IllegalStateException 运行环境缺少 SHA-256 实现时抛出
     */
    private long hash(String key) {
        try {
            return ByteBuffer.wrap(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(key.getBytes(StandardCharsets.UTF_8)))
                    .getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256", e);
        }
    }
}
