package org.example.simple.rate;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 限流器各实现共用的包级工具：参数校验、默认时间源与浮点比较容差。
 */
final class RateLimiters {

    /** 默认时间源：单调递增的纳秒时钟。 */
    static final LongSupplier SYSTEM_NANO_TIME = System::nanoTime;

    /** 每秒的纳秒数。 */
    static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /** 浮点比较容差，避免小数速率累加误差（如 0.9999999）导致应放行的请求被拒绝。 */
    static final double EPSILON = 1e-9;

    /** 工具类不允许实例化。 */
    private RateLimiters() {
    }

    /**
     * 校验整数参数为正数。
     *
     * @param value 参数值
     * @param name 参数名，用于异常消息
     * @return 原参数值
     * @throws IllegalArgumentException 参数不是正数时抛出
     */
    static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必须为正数：" + value);
        }
        return value;
    }

    /**
     * 校验速率为有限正数。
     *
     * @param rate 每秒速率
     * @param name 参数名，用于异常消息
     * @return 原速率
     * @throws IllegalArgumentException 速率不是有限正数时抛出
     */
    static double requirePositiveRate(double rate, String name) {
        if (!Double.isFinite(rate) || rate <= 0) {
            throw new IllegalArgumentException(name + " 必须为有限正数：" + rate);
        }
        return rate;
    }

    /**
     * 校验时间窗口为正并转换为纳秒。
     *
     * @param window 时间窗口
     * @return 窗口纳秒数
     * @throws NullPointerException 窗口为 {@code null} 时抛出
     * @throws IllegalArgumentException 窗口不为正或超出纳秒可表示范围时抛出
     */
    static long toPositiveNanos(Duration window) {
        Objects.requireNonNull(window, "窗口不允许为 null");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("窗口必须为正：" + window);
        }
        try {
            return window.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("窗口超出纳秒可表示范围：" + window, e);
        }
    }

    /**
     * 校验许可数为正数。
     *
     * @param permits 许可数
     * @throws IllegalArgumentException 许可数不是正数时抛出
     */
    static void checkPermits(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("许可数必须为正数：" + permits);
        }
    }

    /**
     * 校验时间源非 {@code null}。
     *
     * @param nanoTimeSource 纳秒时间源
     * @return 原时间源
     */
    static LongSupplier requireClock(LongSupplier nanoTimeSource) {
        return Objects.requireNonNull(nanoTimeSource, "时间源不允许为 null");
    }

    /**
     * 返回不早于上次读数的当前时间，防止时间源回退使经过时间为负。
     * <p>
     * 纳秒时间可能溢出回绕，因此用差值而非直接比较大小判断先后。
     *
     * @param clock 时间源
     * @param last 上次读数
     * @return 当前读数与上次读数中较晚的一个
     */
    static long monotonicNow(LongSupplier clock, long last) {
        long now = clock.getAsLong();
        return now - last > 0 ? now : last;
    }
}
