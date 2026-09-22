package org.example.simple.rpc.discovery;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 用于本地部署及可重复验证的动态发现源。 */
public final class InMemoryServiceDiscovery implements ServiceDiscovery {
    /** 服务名到最新实例快照的映射，新订阅者立即读取此处的当前值。 */
    private final Map<String, List<ServiceInstance>> values = new ConcurrentHashMap<>();
    /** 服务名到订阅回调列表的映射，写少读多，使用写时复制列表保证遍历期间可安全增删。 */
    private final Map<String, CopyOnWriteArrayList<Consumer<List<ServiceInstance>>>> listeners = new ConcurrentHashMap<>();

    /**
     * 发布一份新的实例快照并同步通知所有订阅者。
     *
     * <p>加锁串行发布，保证订阅者收到的快照顺序与发布顺序一致，便于测试中复现实例上下线场景。
     *
     * @param service 服务名称
     * @param instances 最新实例列表，内部会复制为不可变快照
     */
    public synchronized void update(String service, List<ServiceInstance> instances) {
        List<ServiceInstance> snapshot = List.copyOf(instances);
        values.put(service, snapshot);
        listeners.getOrDefault(service, new CopyOnWriteArrayList<>())
                .forEach(listener -> listener.accept(snapshot));
    }

    /**
     * 注册实例变更回调，并立即回调一次当前快照。
     *
     * <p>服务尚无数据时回调空列表，与"发现失败"区分开：空列表表示确实没有可用实例。
     *
     * @param service 服务名称
     * @param listener 实例快照回调
     * @param errors 发现失败回调，内存实现不会触发
     * @return 取消订阅的句柄，关闭后不再收到回调
     */
    @Override
    public synchronized AutoCloseable subscribe(
            String service, Consumer<List<ServiceInstance>> listener, Consumer<Throwable> errors) {
        var group = listeners.computeIfAbsent(service, key -> new CopyOnWriteArrayList<>());
        group.add(listener);
        listener.accept(values.getOrDefault(service, List.of()));
        return () -> group.remove(listener);
    }
}
