package org.example.simple.rpc.config;

import io.netty.channel.WriteBufferWaterMark;

/** 不可变的 RPC 生产参数；所有线程数量均为固定值，可通过构建器手动覆盖。 */
public final class RpcConfig {
    /** 服务端监听连接的 boss EventLoop 线程数。 */
    private final int serverBossThreads;

    /** 服务端已建立连接的 I/O EventLoop 线程数。 */
    private final int serverWorkerThreads;

    /** 客户端所有连接共享的 I/O EventLoop 线程数。 */
    private final int clientIoThreads;

    /** 服务端执行业务方法的固定线程池线程数。 */
    private final int businessThreads;

    /** 客户端和服务端执行编解码转换的固定线程池线程数。 */
    private final int codecThreads;

    /** 客户端完成调用结果及用户回调的固定线程池线程数。 */
    private final int completionThreads;

    /** 服务端业务线程池的有界等待队列容量。 */
    private final int businessQueueCapacity;

    /** 编解码线程池的有界等待队列容量。 */
    private final int codecQueueCapacity;

    /** 单个客户端实例允许登记的最大在途请求数。 */
    private final int maxPendingRequests;

    /** 单条客户端连接允许同时处理的最大在途请求数。 */
    private final int maxRequestsPerConnection;

    /** 单个服务端实例接受的最大客户端连接数。 */
    private final int maxServerConnections;

    /** 单个客户端实例维护的最大服务端连接数。 */
    private final int maxClientConnections;

    /** 服务端监听 Socket 的连接积压队列容量。 */
    private final int backlog;

    /** 建立 TCP 连接的最长等待时间，单位为毫秒。 */
    private final int connectTimeoutMillis;

    /** 单次网络写入的最长等待时间，单位为毫秒。 */
    private final int writeTimeoutMillis;

    /** 一次完整 RPC 调用的总时间预算，单位为毫秒。 */
    private final int requestTimeoutMillis;

    /** 启用 TLS 时完成握手的最长等待时间，单位为毫秒。 */
    private final int handshakeTimeoutMillis;

    /** 连接无写入时发送心跳的间隔，单位为毫秒。 */
    private final int heartbeatIntervalMillis;

    /** 连接持续未读取到数据时判定失活的时间，单位为毫秒。 */
    private final int readIdleTimeoutMillis;

    /** 优雅停机时等待业务在途请求排空的最长时间，单位为毫秒。 */
    private final int drainTimeoutMillis;

    /** 排空后关闭线程、连接等资源的最长时间，单位为毫秒。 */
    private final int shutdownTimeoutMillis;

    /** 单个 RPC 消息体允许的最大字节数，不包含十九字节固定头。 */
    private final int maxMessageLength;

    /** Channel 写缓冲区进入可写恢复状态的低水位字节数。 */
    private final int writeLowWaterMark;

    /** Channel 写缓冲区进入不可写状态的高水位字节数。 */
    private final int writeHighWaterMark;

    /** 单条 Channel 可以预留的最大待写报文字节数。 */
    private final int maxChannelWriteBytes;

    /** 单个客户端或服务端实例可持有的最大逻辑报文字节预算。 */
    private final int maxBufferedBytes;

    /** 单个服务端实例每秒允许处理的请求数。 */
    private final int requestsPerSecond;

    /** 服务端限流令牌桶在空闲时最多积累的令牌数。 */
    private final int burstCapacity;

    private RpcConfig(Builder builder) {
        this.serverBossThreads = positive(builder.serverBossThreads, "serverBossThreads");
        this.serverWorkerThreads = positive(builder.serverWorkerThreads, "serverWorkerThreads");
        this.clientIoThreads = positive(builder.clientIoThreads, "clientIoThreads");
        this.businessThreads = positive(builder.businessThreads, "businessThreads");
        this.codecThreads = positive(builder.codecThreads, "codecThreads");
        this.completionThreads = positive(builder.completionThreads, "completionThreads");
        this.businessQueueCapacity =
                positive(builder.businessQueueCapacity, "businessQueueCapacity");
        this.codecQueueCapacity = positive(builder.codecQueueCapacity, "codecQueueCapacity");
        this.maxPendingRequests = positive(builder.maxPendingRequests, "maxPendingRequests");
        this.maxRequestsPerConnection =
                positive(builder.maxRequestsPerConnection, "maxRequestsPerConnection");
        this.maxServerConnections = positive(builder.maxServerConnections, "maxServerConnections");
        this.maxClientConnections = positive(builder.maxClientConnections, "maxClientConnections");
        this.backlog = positive(builder.backlog, "backlog");
        this.connectTimeoutMillis = positive(builder.connectTimeoutMillis, "connectTimeoutMillis");
        this.writeTimeoutMillis = positive(builder.writeTimeoutMillis, "writeTimeoutMillis");
        this.requestTimeoutMillis = positive(builder.requestTimeoutMillis, "requestTimeoutMillis");
        this.handshakeTimeoutMillis =
                positive(builder.handshakeTimeoutMillis, "handshakeTimeoutMillis");
        this.heartbeatIntervalMillis =
                positive(builder.heartbeatIntervalMillis, "heartbeatIntervalMillis");
        this.readIdleTimeoutMillis =
                positive(builder.readIdleTimeoutMillis, "readIdleTimeoutMillis");
        this.drainTimeoutMillis = positive(builder.drainTimeoutMillis, "drainTimeoutMillis");
        this.shutdownTimeoutMillis =
                positive(builder.shutdownTimeoutMillis, "shutdownTimeoutMillis");
        this.maxMessageLength = positive(builder.maxMessageLength, "maxMessageLength");
        this.writeLowWaterMark = positive(builder.writeLowWaterMark, "writeLowWaterMark");
        this.writeHighWaterMark = positive(builder.writeHighWaterMark, "writeHighWaterMark");
        this.maxChannelWriteBytes = positive(builder.maxChannelWriteBytes, "maxChannelWriteBytes");
        this.maxBufferedBytes = positive(builder.maxBufferedBytes, "maxBufferedBytes");
        this.requestsPerSecond = positive(builder.requestsPerSecond, "requestsPerSecond");
        this.burstCapacity = positive(builder.burstCapacity, "burstCapacity");
        if (writeLowWaterMark >= writeHighWaterMark
                || writeHighWaterMark > maxChannelWriteBytes
                || maxMessageLength > Integer.MAX_VALUE - 19
                || maxChannelWriteBytes < (long) maxMessageLength + 19
                || maxBufferedBytes < (long) maxMessageLength + 19
                || heartbeatIntervalMillis >= readIdleTimeoutMillis) {
            throw new IllegalArgumentException("RPC 参数组合不合法");
        }
        Math.addExact(businessThreads, businessQueueCapacity);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RpcConfig defaults() {
        return builder().build();
    }

    public WriteBufferWaterMark waterMark() {
        return new WriteBufferWaterMark(writeLowWaterMark, writeHighWaterMark);
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须大于零");
        }
        return value;
    }

    public int serverBossThreads() {
        return serverBossThreads;
    }

    public int serverWorkerThreads() {
        return serverWorkerThreads;
    }

    public int clientIoThreads() {
        return clientIoThreads;
    }

    public int businessThreads() {
        return businessThreads;
    }

    public int codecThreads() {
        return codecThreads;
    }

    public int completionThreads() {
        return completionThreads;
    }

    public int businessQueueCapacity() {
        return businessQueueCapacity;
    }

    public int codecQueueCapacity() {
        return codecQueueCapacity;
    }

    public int maxPendingRequests() {
        return maxPendingRequests;
    }

    public int maxRequestsPerConnection() {
        return maxRequestsPerConnection;
    }

    public int maxServerConnections() {
        return maxServerConnections;
    }

    public int maxClientConnections() {
        return maxClientConnections;
    }

    public int backlog() {
        return backlog;
    }

    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int writeTimeoutMillis() {
        return writeTimeoutMillis;
    }

    public int requestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public int handshakeTimeoutMillis() {
        return handshakeTimeoutMillis;
    }

    public int heartbeatIntervalMillis() {
        return heartbeatIntervalMillis;
    }

    public int readIdleTimeoutMillis() {
        return readIdleTimeoutMillis;
    }

    public int drainTimeoutMillis() {
        return drainTimeoutMillis;
    }

    public int shutdownTimeoutMillis() {
        return shutdownTimeoutMillis;
    }

    public int maxMessageLength() {
        return maxMessageLength;
    }

    public int writeLowWaterMark() {
        return writeLowWaterMark;
    }

    public int writeHighWaterMark() {
        return writeHighWaterMark;
    }

    public int maxChannelWriteBytes() {
        return maxChannelWriteBytes;
    }

    public int maxBufferedBytes() {
        return maxBufferedBytes;
    }

    public int requestsPerSecond() {
        return requestsPerSecond;
    }

    public int burstCapacity() {
        return burstCapacity;
    }

    /**
     * RPC 配置构建器。
     *
     * <p>所有参数必须显式为正数；零和负值不会触发自动推导。
     */
    public static final class Builder {
        /** 服务端监听连接的 boss EventLoop 线程数。 */
        private int serverBossThreads = 1;

        /** 服务端已建立连接的 I/O EventLoop 线程数。 */
        private int serverWorkerThreads = 4;

        /** 客户端所有连接共享的 I/O EventLoop 线程数。 */
        private int clientIoThreads = 2;

        /** 服务端执行业务方法的固定线程池线程数。 */
        private int businessThreads = 8;

        /** 客户端和服务端执行编解码转换的固定线程池线程数。 */
        private int codecThreads = 2;

        /** 客户端完成调用结果及用户回调的固定线程池线程数。 */
        private int completionThreads = 2;

        /** 服务端业务线程池的有界等待队列容量。 */
        private int businessQueueCapacity = 256;

        /** 编解码线程池的有界等待队列容量。 */
        private int codecQueueCapacity = 256;

        /** 单个客户端实例允许登记的最大在途请求数。 */
        private int maxPendingRequests = 1024;

        /** 单条客户端连接允许同时处理的最大在途请求数。 */
        private int maxRequestsPerConnection = 128;

        /** 单个服务端实例接受的最大客户端连接数。 */
        private int maxServerConnections = 1024;

        /** 单个客户端实例维护的最大服务端连接数。 */
        private int maxClientConnections = 128;

        /** 服务端监听 Socket 的连接积压队列容量。 */
        private int backlog = 1024;

        /** 建立 TCP 连接的最长等待时间，单位为毫秒。 */
        private int connectTimeoutMillis = 1000;

        /** 单次网络写入的最长等待时间，单位为毫秒。 */
        private int writeTimeoutMillis = 1000;

        /** 一次完整 RPC 调用的总时间预算，单位为毫秒。 */
        private int requestTimeoutMillis = 5000;

        /** 启用 TLS 时完成握手的最长等待时间，单位为毫秒。 */
        private int handshakeTimeoutMillis = 3000;

        /** 连接无写入时发送心跳的间隔，单位为毫秒。 */
        private int heartbeatIntervalMillis = 15000;

        /** 连接持续未读取到数据时判定失活的时间，单位为毫秒。 */
        private int readIdleTimeoutMillis = 45000;

        /** 优雅停机时等待业务在途请求排空的最长时间，单位为毫秒。 */
        private int drainTimeoutMillis = 30000;

        /** 排空后关闭线程、连接等资源的最长时间，单位为毫秒。 */
        private int shutdownTimeoutMillis = 5000;

        /** 单个 RPC 消息体允许的最大字节数，不包含十九字节固定头。 */
        private int maxMessageLength = 8388608;

        /** Channel 写缓冲区进入可写恢复状态的低水位字节数。 */
        private int writeLowWaterMark = 32768;

        /** Channel 写缓冲区进入不可写状态的高水位字节数。 */
        private int writeHighWaterMark = 65536;

        /** 单条 Channel 可以预留的最大待写报文字节数。 */
        private int maxChannelWriteBytes = 16777216;

        /** 单个客户端或服务端实例可持有的最大逻辑报文字节预算。 */
        private int maxBufferedBytes = 67108864;

        /** 单个服务端实例每秒允许处理的请求数。 */
        private int requestsPerSecond = 1000;

        /** 服务端限流令牌桶在空闲时最多积累的令牌数。 */
        private int burstCapacity = 200;

        public Builder serverBossThreads(int value) {
            serverBossThreads = value;
            return this;
        }

        public Builder serverWorkerThreads(int value) {
            serverWorkerThreads = value;
            return this;
        }

        public Builder clientIoThreads(int value) {
            clientIoThreads = value;
            return this;
        }

        public Builder businessThreads(int value) {
            businessThreads = value;
            return this;
        }

        public Builder codecThreads(int value) {
            codecThreads = value;
            return this;
        }

        public Builder completionThreads(int value) {
            completionThreads = value;
            return this;
        }

        public Builder businessQueueCapacity(int value) {
            businessQueueCapacity = value;
            return this;
        }

        public Builder codecQueueCapacity(int value) {
            codecQueueCapacity = value;
            return this;
        }

        public Builder maxPendingRequests(int value) {
            maxPendingRequests = value;
            return this;
        }

        public Builder maxRequestsPerConnection(int value) {
            maxRequestsPerConnection = value;
            return this;
        }

        public Builder maxServerConnections(int value) {
            maxServerConnections = value;
            return this;
        }

        public Builder maxClientConnections(int value) {
            maxClientConnections = value;
            return this;
        }

        public Builder backlog(int value) {
            backlog = value;
            return this;
        }

        public Builder connectTimeoutMillis(int value) {
            connectTimeoutMillis = value;
            return this;
        }

        public Builder writeTimeoutMillis(int value) {
            writeTimeoutMillis = value;
            return this;
        }

        public Builder requestTimeoutMillis(int value) {
            requestTimeoutMillis = value;
            return this;
        }

        public Builder handshakeTimeoutMillis(int value) {
            handshakeTimeoutMillis = value;
            return this;
        }

        public Builder heartbeatIntervalMillis(int value) {
            heartbeatIntervalMillis = value;
            return this;
        }

        public Builder readIdleTimeoutMillis(int value) {
            readIdleTimeoutMillis = value;
            return this;
        }

        public Builder drainTimeoutMillis(int value) {
            drainTimeoutMillis = value;
            return this;
        }

        public Builder shutdownTimeoutMillis(int value) {
            shutdownTimeoutMillis = value;
            return this;
        }

        public Builder maxMessageLength(int value) {
            maxMessageLength = value;
            return this;
        }

        public Builder writeLowWaterMark(int value) {
            writeLowWaterMark = value;
            return this;
        }

        public Builder writeHighWaterMark(int value) {
            writeHighWaterMark = value;
            return this;
        }

        public Builder maxChannelWriteBytes(int value) {
            maxChannelWriteBytes = value;
            return this;
        }

        public Builder maxBufferedBytes(int value) {
            maxBufferedBytes = value;
            return this;
        }

        public Builder requestsPerSecond(int value) {
            requestsPerSecond = value;
            return this;
        }

        public Builder burstCapacity(int value) {
            burstCapacity = value;
            return this;
        }

        public RpcConfig build() {
            return new RpcConfig(this);
        }
    }
}
