package org.example.simple.http;

import java.time.Duration;

/**
 * HTTP 共享连接池的不可变配置。
 *
 * @param maxTotalConnections 连接池总连接上限
 * @param maxConnectionsPerRoute 单个目标路由连接上限
 * @param connectionTimeToLive 连接最长存活时间
 * @param validateAfterInactivity 连接复用前触发有效性校验的空闲时长
 * @param idleEvictionTimeout 空闲连接回收时长
 */
public record HttpConnectionPoolConfig(
    int maxTotalConnections,
    int maxConnectionsPerRoute,
    Duration connectionTimeToLive,
    Duration validateAfterInactivity,
    Duration idleEvictionTimeout) {

    /** 默认总连接上限。 */
    public static final int DEFAULT_MAX_TOTAL_CONNECTIONS = 200;

    /** 默认单路由连接上限。 */
    public static final int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = 50;

    /** 默认连接最长存活时间。 */
    public static final Duration DEFAULT_CONNECTION_TIME_TO_LIVE = Duration.ofMinutes(5);

    /** 默认复用前空闲校验时长。 */
    public static final Duration DEFAULT_VALIDATE_AFTER_INACTIVITY = Duration.ofSeconds(5);

    /** 默认空闲连接回收时长。 */
    public static final Duration DEFAULT_IDLE_EVICTION_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 校验并创建连接池配置。
     *
     * @param maxTotalConnections 连接池总连接上限
     * @param maxConnectionsPerRoute 单个目标路由连接上限
     * @param connectionTimeToLive 连接最长存活时间
     * @param validateAfterInactivity 连接复用前触发有效性校验的空闲时长
     * @param idleEvictionTimeout 空闲连接回收时长
     */
    public HttpConnectionPoolConfig {
        if (maxTotalConnections <= 0) {
            throw new IllegalArgumentException("连接池总连接上限必须大于零");
        }
        if (maxConnectionsPerRoute <= 0) {
            throw new IllegalArgumentException("单路由连接上限必须大于零");
        }
        if (maxConnectionsPerRoute > maxTotalConnections) {
            throw new IllegalArgumentException("单路由连接上限不能大于连接池总连接上限");
        }
        connectionTimeToLive = HttpTimeouts.requireValid(connectionTimeToLive, "连接最长存活时间");
        validateAfterInactivity = HttpTimeouts.requireValid(validateAfterInactivity, "连接空闲校验时长");
        idleEvictionTimeout = HttpTimeouts.requireValid(idleEvictionTimeout, "空闲连接回收时长");
    }

    /**
     * 创建生产推荐的默认连接池配置。
     *
     * @return 默认连接池配置
     */
    public static HttpConnectionPoolConfig defaults() {
        return builder().build();
    }

    /**
     * 创建连接池配置构建器。
     *
     * @return 配置构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * HTTP 连接池配置构建器。
     */
    public static final class Builder {

        private int maxTotalConnections = DEFAULT_MAX_TOTAL_CONNECTIONS;
        private int maxConnectionsPerRoute = DEFAULT_MAX_CONNECTIONS_PER_ROUTE;
        private Duration connectionTimeToLive = DEFAULT_CONNECTION_TIME_TO_LIVE;
        private Duration validateAfterInactivity = DEFAULT_VALIDATE_AFTER_INACTIVITY;
        private Duration idleEvictionTimeout = DEFAULT_IDLE_EVICTION_TIMEOUT;

        private Builder() {
        }

        /**
         * 设置连接池总连接上限。
         *
         * @param maxTotalConnections 总连接上限
         * @return 当前构建器
         */
        public Builder maxTotalConnections(int maxTotalConnections) {
            this.maxTotalConnections = maxTotalConnections;
            return this;
        }

        /**
         * 设置单个目标路由连接上限。
         *
         * @param maxConnectionsPerRoute 单路由连接上限
         * @return 当前构建器
         */
        public Builder maxConnectionsPerRoute(int maxConnectionsPerRoute) {
            this.maxConnectionsPerRoute = maxConnectionsPerRoute;
            return this;
        }

        /**
         * 设置连接最长存活时间。
         *
         * @param connectionTimeToLive 连接最长存活时间
         * @return 当前构建器
         */
        public Builder connectionTimeToLive(Duration connectionTimeToLive) {
            this.connectionTimeToLive = connectionTimeToLive;
            return this;
        }

        /**
         * 设置连接复用前触发有效性校验的空闲时长。
         *
         * @param validateAfterInactivity 空闲校验时长
         * @return 当前构建器
         */
        public Builder validateAfterInactivity(Duration validateAfterInactivity) {
            this.validateAfterInactivity = validateAfterInactivity;
            return this;
        }

        /**
         * 设置空闲连接回收时长。
         *
         * @param idleEvictionTimeout 空闲连接回收时长
         * @return 当前构建器
         */
        public Builder idleEvictionTimeout(Duration idleEvictionTimeout) {
            this.idleEvictionTimeout = idleEvictionTimeout;
            return this;
        }

        /**
         * 构建不可变连接池配置。
         *
         * @return 连接池配置
         */
        public HttpConnectionPoolConfig build() {
            return new HttpConnectionPoolConfig(
                maxTotalConnections,
                maxConnectionsPerRoute,
                connectionTimeToLive,
                validateAfterInactivity,
                idleEvictionTimeout);
        }
    }
}
