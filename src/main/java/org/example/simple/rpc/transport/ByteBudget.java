package org.example.simple.rpc.transport;

import java.util.concurrent.atomic.AtomicLong;

/** 实例内共享的报文逻辑字节预算。 */
public final class ByteBudget {
    /** 允许框架持有的最大逻辑报文字节数。 */
    private final long limit;

    /** 已被准入且尚未释放的逻辑报文字节数。 */
    private final AtomicLong used = new AtomicLong();

    /**
     * 创建字节预算。
     *
     * @param limit 允许持有的最大逻辑报文字节数
     */
    public ByteBudget(long limit) {
        this.limit = limit;
    }

    /**
     * 原子预留指定字节数。
     *
     * @param size 需要预留的字节数
     * @return 预留成功时为 {@code true}；超过预算或字节数非法时为 {@code false}
     */
    public boolean acquire(long size) {
        for (; ; ) {
            long current = used.get();
            if (size < 0 || size > limit - current) {
                return false;
            }
            if (used.compareAndSet(current, current + size)) {
                return true;
            }
        }
    }

    /**
     * 释放此前预留的字节数。
     *
     * @param size 要释放的字节数
     */
    public void release(long size) {
        used.addAndGet(-size);
    }

    /**
     * 返回当前已使用的逻辑报文字节数。
     *
     * @return 当前字节占用
     */
    public long used() {
        return used.get();
    }
}
