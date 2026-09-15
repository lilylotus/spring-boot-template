package org.example.simple.rpc.discovery;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;

import java.util.*;
import java.util.function.Consumer;

/** Nacos 注册与发现适配器，地址及凭证通过外部 Properties 提供。 */
public final class NacosServiceDiscovery implements ServiceDiscovery {
    private final NamingService naming;
    private final String group;
    private final String version;
    private final java.util.concurrent.ScheduledExecutorService health =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    org.example.simple.rpc.transport.RpcExecutors.factory("nacos-health"));

    public NacosServiceDiscovery(Properties properties, String group, String version) {
        try {
            this.naming = NacosFactory.createNamingService(properties);
        } catch (Exception e) {
            throw new IllegalStateException("Nacos 初始化失败", e);
        }
        this.group = Objects.requireNonNull(group);
        this.version = Objects.requireNonNull(version);
    }

    private String name(String service) {
        return service + ":" + version;
    }

    @Override
    public AutoCloseable subscribe(
            String service, Consumer<List<ServiceInstance>> listener, Consumer<Throwable> errors) {
        EventListener events =
                event -> {
                    try {
                        if (event instanceof NamingEvent change) {
                            listener.accept(
                                    change.getInstances().stream()
                                            .filter(i -> i.isHealthy() && i.isEnabled())
                                            .map(
                                                    i ->
                                                            new ServiceInstance(
                                                                    i.getIp() + ":" + i.getPort(),
                                                                    i.getIp(),
                                                                    i.getPort(),
                                                                    i.getWeight()))
                                            .toList());
                        }
                    } catch (RuntimeException error) {
                        errors.accept(error);
                    }
                };
        try {
            naming.subscribe(name(service), group, events);
        } catch (Exception error) {
            errors.accept(error);
        }
        var check =
                health.scheduleWithFixedDelay(
                        () -> {
                            try {
                                if (!"UP".equals(naming.getServerStatus())) {
                                    throw new IllegalStateException("Nacos 不可用");
                                }
                                listener.accept(
                                        naming.getAllInstances(name(service), group).stream()
                                                .filter(i -> i.isHealthy() && i.isEnabled())
                                                .map(
                                                        i ->
                                                                new ServiceInstance(
                                                                        i.getIp()
                                                                                + ":"
                                                                                + i.getPort(),
                                                                        i.getIp(),
                                                                        i.getPort(),
                                                                        i.getWeight()))
                                                .toList());
                            } catch (Exception error) {
                                errors.accept(error);
                            }
                        },
                        0,
                        5,
                        java.util.concurrent.TimeUnit.SECONDS);
        return () -> {
            check.cancel(false);
            naming.unsubscribe(name(service), group, events);
        };
    }

    public AutoCloseable register(String service, ServiceInstance instance) {
        Instance entry = new Instance();
        entry.setIp(instance.host());
        entry.setPort(instance.port());
        entry.setWeight(instance.weight());
        entry.setEphemeral(true);
        try {
            naming.registerInstance(name(service), group, entry);
        } catch (Exception e) {
            throw new IllegalStateException("Nacos 服务注册失败", e);
        }
        return () -> naming.deregisterInstance(name(service), group, entry);
    }

    @Override
    public void close() {
        health.shutdownNow();
        try {
            naming.shutDown();
        } catch (Exception e) {
            throw new IllegalStateException("Nacos 关闭失败", e);
        }
    }
}
