package org.example.simple.rpc.loadbalance;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.nio.ByteBuffer;

/** 基于稳定实例标识及 128 个虚拟节点的一致性哈希。 */
public final class ConsistentHashLoadBalancer implements LoadBalancer {
    public Candidate select(List<Candidate> candidates, String key) {
        if (key == null || key.isBlank()) { throw new IllegalArgumentException("一致性哈希必须指定路由键"); }
        TreeMap<Long, Candidate> ring = new TreeMap<>();
        candidates.stream().sorted(Comparator.comparing(c -> c.instance().id())).forEach(candidate -> {
            for (int i = 0; i < 128; i++) { ring.put(hash(candidate.instance().id() + "#" + i), candidate); }
        });
        if (ring.isEmpty()) { throw new IllegalStateException("没有可用服务实例"); }
        var entry = ring.ceilingEntry(hash(key));
        return (entry == null ? ring.firstEntry() : entry).getValue();
    }
    private long hash(String key) {
        try { return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
            .digest(key.getBytes(StandardCharsets.UTF_8))).getLong(); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("缺少 SHA-256", e); }
    }
}
