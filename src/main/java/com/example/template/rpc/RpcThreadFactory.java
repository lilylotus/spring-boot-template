package com.example.template.rpc;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 给 RPC 框架内部各类线程(IO 线程、业务线程池、心跳/超时定时器线程)统一命名的线程工厂，
 * 线程名格式为“前缀-序号”，替代 JDK 默认的 {@code pool-N-thread-M}，便于日志和线程 dump 排查
 * 某个线程具体属于哪个组件。
 */
public class RpcThreadFactory implements ThreadFactory {

    private final String namePrefix;

    private final boolean daemon;

    /** 线程序号生成器，从 1 开始自增，保证并发创建线程时序号不重复。 */
    private final AtomicInteger sequence = new AtomicInteger(1);

    public RpcThreadFactory(String namePrefix, boolean daemon) {
        this.namePrefix = namePrefix;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, namePrefix + "-" + sequence.getAndIncrement());
        thread.setDaemon(daemon);
        return thread;
    }

}
