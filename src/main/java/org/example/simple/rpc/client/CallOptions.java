package org.example.simple.rpc.client;

/** 单次调用选项；默认不重试，使用 JSON。 */
public record CallOptions(byte serializerId, long timeoutMillis, String routingKey, boolean idempotent, int retries) {
    public CallOptions {
        if (serializerId <= 0 || timeoutMillis <= 0 || timeoutMillis > Integer.MAX_VALUE
            || retries < 0 || retries > 1 || retries > 0 && !idempotent) {
            throw new IllegalArgumentException("调用配置不合法");
        }
    }
    public static CallOptions defaults() { return new CallOptions((byte) 1, 5000, null, false, 0); }
}
