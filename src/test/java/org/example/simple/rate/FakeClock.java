package org.example.simple.rate;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 测试用可控纳秒时钟：时间只在测试显式推进或设置时变化。
 */
final class FakeClock implements LongSupplier {

    /** 每毫秒的纳秒数。 */
    static final long NANOS_PER_MILLI = 1_000_000L;

    /** 当前纳秒读数。 */
    private final AtomicLong nanos;

    /**
     * 以指定初始读数创建时钟。
     *
     * @param startNanos 初始纳秒读数
     */
    FakeClock(long startNanos) {
        this.nanos = new AtomicLong(startNanos);
    }

    /** 以任意非零初始读数创建时钟，避免实现依赖时间从 0 开始。 */
    FakeClock() {
        this(123_456_789L);
    }

    @Override
    public long getAsLong() {
        return nanos.get();
    }

    /**
     * 推进指定毫秒数，可为负数以模拟时间回退。
     *
     * @param millis 推进的毫秒数
     */
    void advanceMillis(long millis) {
        nanos.addAndGet(millis * NANOS_PER_MILLI);
    }

    /**
     * 推进指定纳秒数。
     *
     * @param delta 推进的纳秒数
     */
    void advanceNanos(long delta) {
        nanos.addAndGet(delta);
    }
}
