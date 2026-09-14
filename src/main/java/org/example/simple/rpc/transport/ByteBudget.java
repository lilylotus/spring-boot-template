package org.example.simple.rpc.transport;

import java.util.concurrent.atomic.AtomicLong;

/** 实例内共享的报文逻辑字节预算。 */
public final class ByteBudget {
    private final long limit;
    private final AtomicLong used = new AtomicLong();
    public ByteBudget(long limit) { this.limit = limit; }
    public boolean acquire(long size) {
        for (;;) {
            long current = used.get();
            if (size < 0 || size > limit - current) { return false; }
            if (used.compareAndSet(current, current + size)) { return true; }
        }
    }
    public void release(long size) { used.addAndGet(-size); }
    public long used() { return used.get(); }
}
