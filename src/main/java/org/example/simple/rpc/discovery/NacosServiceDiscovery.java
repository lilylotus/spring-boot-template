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
    /** Nacos 命名服务客户端，负责注册、订阅与服务端状态查询。 */
    private final NamingService naming;
    /** Nacos 分组名，注册与订阅都限定在该分组内，用于隔离环境。 */
    private final String group;
    /** 服务版本号，拼接在服务名之后形成实际注册名，实现同名服务的多版本共存。 */
    private final String version;
    /** 单线程健康巡检调度器，周期性兜底拉取实例，弥补订阅事件丢失的情况。 */
    private final java.util.concurrent.ScheduledExecutorService health =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    org.example.simple.rpc.transport.RpcExecutors.factory("nacos-health"));

    /**
     * 创建 Nacos 发现适配器。
     *
     * @param properties Nacos 客户端配置，包含服务地址、命名空间与凭证等
     * @param group 分组名
     * @param version 服务版本号
     * @throws IllegalStateException 当 Nacos 客户端初始化失败时抛出
     */
    public NacosServiceDiscovery(Properties properties, String group, String version) {
        try {
            this.naming = NacosFactory.createNamingService(properties);
        } catch (Exception e) {
            throw new IllegalStateException("Nacos 初始化失败", e);
        }
        this.group = Objects.requireNonNull(group);
        this.version = Objects.requireNonNull(version);
    }

    /**
     * 拼接实际注册到 Nacos 的服务名。
     *
     * @param service 逻辑服务名
     * @return "服务名:版本号"形式的注册名
     */
    private String name(String service) {
        return service + ":" + version;
    }

    /**
     * 订阅服务实例变更，并启动周期性兜底巡检。
     *
     * <p>两条数据来源互为补充：Nacos 推送事件保证变更及时，5 秒一次的巡检在推送丢失或连接异常时
     * 重新拉取全量实例。两者都只保留健康且启用的实例，并把 Nacos 权重透传给负载均衡器。
     * 巡检发现 Nacos 自身不可用时走失败回调，由客户端据此判断实例缓存是否已过期。
     *
     * @param service 逻辑服务名
     * @param listener 实例快照回调
     * @param errors 发现失败回调
     * @return 取消订阅的句柄，关闭时同时停止巡检并退订
     */
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

    /**
     * 把本地实例注册到 Nacos。
     *
     * <p>注册为临时实例：进程异常退出后由 Nacos 通过心跳超时自动摘除，无需人工清理。
     *
     * @param service 逻辑服务名
     * @param instance 待注册的本地实例信息
     * @return 反注册句柄，关闭时主动下线该实例
     * @throws IllegalStateException 注册失败时抛出
     */
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

    /**
     * 停止巡检并关闭 Nacos 客户端。
     *
     * @throws IllegalStateException 关闭 Nacos 客户端失败时抛出
     */
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
