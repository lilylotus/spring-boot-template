package org.example.simple.util.echo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * echo 服务端压测配置，创建时校验全部字段，非法值抛出 {@link IllegalArgumentException}。
 *
 * @param host                  目标服务端地址
 * @param port                  目标服务端端口，取值 {@code 1-65535}
 * @param clients               目标连接数
 * @param durationSeconds       运行时长（秒），从开始建连起算；{@code 0} 表示一直运行直到手动停止
 * @param connectRate           每秒最多发起的建连数
 * @param maxPendingConnects    同时处于建连中的最大连接数
 * @param intervalMillis        单连接收齐回显后到下一次发送的间隔（毫秒）；{@code 0} 表示收到即发
 * @param minMessageSize        每次请求随机字符串的最小长度（字节）
 * @param maxMessageSize        每次请求随机字符串的最大长度（字节），两者相等即为固定长度
 * @param connectTimeoutMillis  建连超时（毫秒）
 * @param responseTimeoutMillis 回显超时（毫秒），发送后在此时间内未收齐视为超时
 * @param localAddresses        本地绑定 IP 列表，按连接序号轮询使用；为空时由系统选择
 * @param ioThreads             IO 线程数
 * @param reportIntervalSeconds 统计输出间隔（秒）
 */
public record EchoLoadTestConfig(
        String host, int port,
        int clients, long durationSeconds,
        int connectRate, int maxPendingConnects,
        long intervalMillis, int minMessageSize,
        int maxMessageSize, int connectTimeoutMillis,
        long responseTimeoutMillis, List<String> localAddresses,
        int ioThreads, long reportIntervalSeconds) {

    /** 默认目标地址。 */
    public static final String DEFAULT_HOST = "10.10.10.206";

    /** 默认目标端口。 */
    public static final int DEFAULT_PORT = 19090;

    /** 校验并规范化配置字段。 */
    public EchoLoadTestConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("目标地址 host 不能为空");
        }
        host = host.trim();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("目标端口 port 必须在 1-65535 之间：" + port);
        }
        requirePositive("clients", clients);
        requireNonNegative("duration", durationSeconds);
        requirePositive("connect-rate", connectRate);
        requirePositive("max-pending-connects", maxPendingConnects);
        requireNonNegative("interval-ms", intervalMillis);
        requirePositive("min-message-size", minMessageSize);
        requirePositive("max-message-size", maxMessageSize);
        if (minMessageSize > maxMessageSize) {
            throw new IllegalArgumentException(
                    "最小报文长度 min-message-size 不能大于最大报文长度 max-message-size："
                            + minMessageSize + " > " + maxMessageSize);
        }
        requirePositive("connect-timeout-ms", connectTimeoutMillis);
        requirePositive("response-timeout-ms", responseTimeoutMillis);
        requirePositive("io-threads", ioThreads);
        requirePositive("report-interval-seconds", reportIntervalSeconds);
        Objects.requireNonNull(localAddresses, "本地绑定地址列表 localAddresses 不能为 null");
        List<String> normalized = new ArrayList<>(localAddresses.size());
        for (String address : localAddresses) {
            if (address == null || address.isBlank()) {
                throw new IllegalArgumentException("本地绑定地址 local-addresses 不能包含空值");
            }
            normalized.add(address.trim());
        }
        localAddresses = List.copyOf(normalized);
    }

    /**
     * 返回全部使用默认值的配置。
     *
     * @return 默认配置
     */
    public static EchoLoadTestConfig defaults() {
        return builder().build();
    }

    /**
     * 创建以默认值初始化的构建器。
     *
     * @return 新构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 是否为一直运行模式（{@code durationSeconds == 0}）。
     *
     * @return 一直运行时为 {@code true}
     */
    public boolean isForever() {
        return durationSeconds == 0;
    }

    /** 校验参数大于 0。 */
    private static void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("参数 " + name + " 必须大于 0：" + value);
        }
    }

    /** 校验参数不小于 0。 */
    private static void requireNonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("参数 " + name + " 不能为负数：" + value);
        }
    }

    /**
     * {@link EchoLoadTestConfig} 构建器，各字段初始为默认值，{@link #build()} 时统一校验。
     */
    public static final class Builder {

        /** 目标地址。 */
        private String host = DEFAULT_HOST;

        /** 目标端口。 */
        private int port = DEFAULT_PORT;

        /** 目标连接数。 */
        private int clients = 1000;

        /** 运行时长（秒）。 */
        private long durationSeconds = 60;

        /** 每秒建连数。 */
        private int connectRate = 1000;

        /** 最大并发建连数。 */
        private int maxPendingConnects = 1000;

        /** 请求间隔（毫秒）。 */
        private long intervalMillis = 1000;

        /** 随机报文最小长度。 */
        private int minMessageSize = 16;

        /** 随机报文最大长度。 */
        private int maxMessageSize = 128;

        /** 建连超时（毫秒）。 */
        private int connectTimeoutMillis = 5000;

        /** 回显超时（毫秒）。 */
        private long responseTimeoutMillis = 5000;

        /** 本地绑定 IP 列表。 */
        private List<String> localAddresses = List.of();

        /** IO 线程数。 */
        private int ioThreads = Runtime.getRuntime().availableProcessors();

        /** 统计输出间隔（秒）。 */
        private long reportIntervalSeconds = 1;

        /** 仅允许通过 {@link EchoLoadTestConfig#builder()} 创建。 */
        private Builder() {
        }

        /**
         * 设置目标地址。
         *
         * @param host 目标地址
         * @return 当前构建器
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * 设置目标端口。
         *
         * @param port 目标端口
         * @return 当前构建器
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * 设置目标连接数。
         *
         * @param clients 目标连接数
         * @return 当前构建器
         */
        public Builder clients(int clients) {
            this.clients = clients;
            return this;
        }

        /**
         * 设置运行时长（秒），{@code 0} 表示一直运行。
         *
         * @param durationSeconds 运行时长（秒）
         * @return 当前构建器
         */
        public Builder durationSeconds(long durationSeconds) {
            this.durationSeconds = durationSeconds;
            return this;
        }

        /**
         * 设置每秒最多发起的建连数。
         *
         * @param connectRate 每秒最多发起的建连数
         * @return 当前构建器
         */
        public Builder connectRate(int connectRate) {
            this.connectRate = connectRate;
            return this;
        }

        /**
         * 设置同时处于建连中的最大连接数。
         *
         * @param maxPendingConnects 同时处于建连中的最大连接数
         * @return 当前构建器
         */
        public Builder maxPendingConnects(int maxPendingConnects) {
            this.maxPendingConnects = maxPendingConnects;
            return this;
        }

        /**
         * 设置请求间隔（毫秒），{@code 0} 表示收到回显即发送下一次。
         *
         * @param intervalMillis 请求间隔（毫秒）
         * @return 当前构建器
         */
        public Builder intervalMillis(long intervalMillis) {
            this.intervalMillis = intervalMillis;
            return this;
        }

        /**
         * 设置每次请求随机字符串的最小长度。
         *
         * @param minMessageSize 最小长度（字节）
         * @return 当前构建器
         */
        public Builder minMessageSize(int minMessageSize) {
            this.minMessageSize = minMessageSize;
            return this;
        }

        /**
         * 设置每次请求随机字符串的最大长度。
         *
         * @param maxMessageSize 最大长度（字节）
         * @return 当前构建器
         */
        public Builder maxMessageSize(int maxMessageSize) {
            this.maxMessageSize = maxMessageSize;
            return this;
        }

        /**
         * 同时设置最小与最大长度，使每次请求为固定长度的随机字符串。
         *
         * @param messageSize 固定长度（字节）
         * @return 当前构建器
         */
        public Builder fixedMessageSize(int messageSize) {
            this.minMessageSize = messageSize;
            this.maxMessageSize = messageSize;
            return this;
        }

        /**
         * 设置建连超时（毫秒）。
         *
         * @param connectTimeoutMillis 建连超时（毫秒）
         * @return 当前构建器
         */
        public Builder connectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        /**
         * 设置回显超时（毫秒）。
         *
         * @param responseTimeoutMillis 回显超时（毫秒）
         * @return 当前构建器
         */
        public Builder responseTimeoutMillis(long responseTimeoutMillis) {
            this.responseTimeoutMillis = responseTimeoutMillis;
            return this;
        }

        /**
         * 设置本地绑定 IP 列表。
         *
         * @param localAddresses 本地绑定 IP 列表
         * @return 当前构建器
         */
        public Builder localAddresses(List<String> localAddresses) {
            this.localAddresses = localAddresses;
            return this;
        }

        /**
         * 设置 IO 线程数。
         *
         * @param ioThreads  IO 线程数
         * @return 当前构建器
         */
        public Builder ioThreads(int ioThreads) {
            this.ioThreads = ioThreads;
            return this;
        }

        /**
         * 设置统计输出间隔（秒）。
         *
         * @param reportIntervalSeconds 统计输出间隔（秒）
         * @return 当前构建器
         */
        public Builder reportIntervalSeconds(long reportIntervalSeconds) {
            this.reportIntervalSeconds = reportIntervalSeconds;
            return this;
        }

        /**
         * 校验并构建配置。
         *
         * @return 不可变配置
         * @throws IllegalArgumentException 任一字段取值非法
         */
        public EchoLoadTestConfig build() {
            return new EchoLoadTestConfig(
                    host, port,
                    clients, durationSeconds,
                    connectRate, maxPendingConnects,
                    intervalMillis, minMessageSize,
                    maxMessageSize, connectTimeoutMillis,
                    responseTimeoutMillis, localAddresses,
                    ioThreads, reportIntervalSeconds);
        }
    }
}
