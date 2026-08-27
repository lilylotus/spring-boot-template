package org.example.simple.http;

import java.time.Duration;
import java.util.Objects;

/**
 * HTTP 超时配置校验工具。
 */
final class HttpTimeouts {

    private HttpTimeouts() {
    }

    static Duration requireValid(Duration timeout, String name) {
        Objects.requireNonNull(timeout, name + "不能为空");
        final long timeoutMillis;
        try {
            timeoutMillis = timeout.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + "超出可表达范围", exception);
        }
        if (timeout.isNegative() || timeout.isZero() || timeoutMillis <= 0) {
            throw new IllegalArgumentException(name + "必须至少为一毫秒");
        }
        return timeout;
    }
}
