package org.example.simple.rpc.discovery;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 用于本地部署及可重复验证的动态发现源。 */
public final class InMemoryServiceDiscovery implements ServiceDiscovery {
    private final Map<String, List<ServiceInstance>> values = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<List<ServiceInstance>>>> listeners =
            new ConcurrentHashMap<>();

    public synchronized void update(String service, List<ServiceInstance> instances) {
        List<ServiceInstance> snapshot = List.copyOf(instances);
        values.put(service, snapshot);
        listeners
                .getOrDefault(service, new CopyOnWriteArrayList<>())
                .forEach(listener -> listener.accept(snapshot));
    }

    @Override
    public synchronized AutoCloseable subscribe(
            String service, Consumer<List<ServiceInstance>> listener, Consumer<Throwable> errors) {
        var group = listeners.computeIfAbsent(service, key -> new CopyOnWriteArrayList<>());
        group.add(listener);
        listener.accept(values.getOrDefault(service, List.of()));
        return () -> group.remove(listener);
    }
}
