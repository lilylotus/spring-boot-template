package org.example.simple.rpc.client;

/**
 * 单次调用选项；默认不重试，使用 JSON。
 *
 * @param serializerId 本次调用使用的序列化器标识，1 为 JSON、2 为 Protostuff
 * @param timeoutMillis 调用超时预算，单位为毫秒；带重试时为包含全部重试的总预算
 * @param routingKey 路由键，一致性哈希策略据此把同键请求固定到同一实例；其它策略忽略
 * @param idempotent 方法是否幂等，只有幂等方法才允许重试
 * @param retries 额外重试次数，取值 0 或 1
 */
public record CallOptions(
        byte serializerId, long timeoutMillis, String routingKey, boolean idempotent, int retries) {
    /**
     * 校验选项取值，并强制"仅幂等方法可重试"。
     *
     * @throws IllegalArgumentException 当序列化标识非正、超时越界、重试次数越界，
     *     或对非幂等方法配置重试时抛出
     */
    public CallOptions {
        if (serializerId <= 0
                || timeoutMillis <= 0
                || timeoutMillis > Integer.MAX_VALUE
                || retries < 0
                || retries > 1
                || retries > 0 && !idempotent) {
            throw new IllegalArgumentException("调用配置不合法");
        }
    }

    /**
     * 返回默认选项：JSON 编码、5 秒超时、不指定路由键、按非幂等处理且不重试。
     *
     * @return 默认调用选项
     */
    public static CallOptions defaults() {
        return new CallOptions((byte) 1, 5000, null, false, 0);
    }
}
