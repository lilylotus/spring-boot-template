package org.example.simple.rpc.discovery;

import java.util.List;
import java.util.function.Consumer;

/** 订阅事件中失败与有效空列表分开表达；关闭订阅后不得继续使用回调。 */
public interface ServiceDiscovery extends AutoCloseable {
    AutoCloseable subscribe(String service, Consumer<List<ServiceInstance>> instances, Consumer<Throwable> errors);
    @Override default void close() { }
}
