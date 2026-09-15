package org.example.simple.rpc.discovery;

import java.util.List;
import java.util.function.Consumer;

/** 订阅事件中失败与有效空列表分开表达；关闭订阅后不得继续使用回调。 */
public interface ServiceDiscovery extends AutoCloseable {
    /**
     * 订阅指定服务的实例快照与发现失败事件。
     *
     * @param service 服务名称
     * @param instances 实例快照回调
     * @param errors 发现失败回调
     * @return 用于取消当前订阅的资源句柄
     */
    AutoCloseable subscribe(
            String service, Consumer<List<ServiceInstance>> instances, Consumer<Throwable> errors);

    /** 释放发现适配器持有的资源。 */
    @Override
    default void close() {}
}
