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

    /**
     * 校验并固化全部生产参数。
     *
     * <p>除逐项要求为正数外，还校验参数之间的组合关系，把配置错误挡在启动阶段：
     *
     * <ul>
     *   <li>写缓冲低水位必须小于高水位，高水位不得超过单连接待写上限，否则背压无法正常生效
     *   <li>单连接待写上限与实例字节预算都必须能容纳一个最大消息（含 19 字节固定头），
     *       否则最大尺寸的合法消息将永远无法发出
     *   <li>心跳间隔必须小于读空闲时间，否则心跳还没发出连接就已被判定失活
     *   <li>业务线程数与队列容量之和不得溢出，该和值即服务端在途请求许可数
     * </ul>
     *
     * @param builder 已填充参数的构建器
     * @throws IllegalArgumentException 任一参数非正或参数组合不合法时抛出
     */
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

    /**
     * 创建参数构建器，未显式设置的参数保留默认值。
     *
     * @return 新的构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回全部使用默认值的生产参数。
     *
     * @return 默认生产参数
     */
    public static RpcConfig defaults() {
        return builder().build();
    }

    /**
     * 返回 Netty 写缓冲水位配置。
     *
     * <p>出站字节超过高水位时连接转为不可写，跌回低水位后恢复，据此对上游形成背压。
     *
     * @return 写缓冲水位配置
     */
    public WriteBufferWaterMark waterMark() {
        return new WriteBufferWaterMark(writeLowWaterMark, writeHighWaterMark);
    }

    /**
     * 校验参数为正数。
     *
     * @param value 参数取值
     * @param name 参数名，用于异常信息中定位具体参数
     * @return 校验通过的取值
     * @throws IllegalArgumentException 取值非正时抛出
     */
    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须大于零");
        }
        return value;
    }

    /**
     * 返回服务端监听连接的 boss EventLoop 线程数。
     *
     * @return 服务端监听连接的 boss EventLoop 线程数
     */
    public int serverBossThreads() {
        return serverBossThreads;
    }

    /**
     * 返回服务端已建立连接的 I/O EventLoop 线程数。
     *
     * @return 服务端已建立连接的 I/O EventLoop 线程数
     */
    public int serverWorkerThreads() {
        return serverWorkerThreads;
    }

    /**
     * 返回客户端所有连接共享的 I/O EventLoop 线程数。
     *
     * @return 客户端所有连接共享的 I/O EventLoop 线程数
     */
    public int clientIoThreads() {
        return clientIoThreads;
    }

    /**
     * 返回服务端执行业务方法的固定线程池线程数。
     *
     * @return 服务端执行业务方法的固定线程池线程数
     */
    public int businessThreads() {
        return businessThreads;
    }

    /**
     * 返回客户端和服务端执行编解码转换的固定线程池线程数。
     *
     * @return 客户端和服务端执行编解码转换的固定线程池线程数
     */
    public int codecThreads() {
        return codecThreads;
    }

    /**
     * 返回客户端完成调用结果及用户回调的固定线程池线程数。
     *
     * @return 客户端完成调用结果及用户回调的固定线程池线程数
     */
    public int completionThreads() {
        return completionThreads;
    }

    /**
     * 返回服务端业务线程池的有界等待队列容量。
     *
     * @return 服务端业务线程池的有界等待队列容量
     */
    public int businessQueueCapacity() {
        return businessQueueCapacity;
    }

    /**
     * 返回编解码线程池的有界等待队列容量。
     *
     * @return 编解码线程池的有界等待队列容量
     */
    public int codecQueueCapacity() {
        return codecQueueCapacity;
    }

    /**
     * 返回单个客户端实例允许登记的最大在途请求数。
     *
     * @return 单个客户端实例允许登记的最大在途请求数
     */
    public int maxPendingRequests() {
        return maxPendingRequests;
    }

    /**
     * 返回单条客户端连接允许同时处理的最大在途请求数。
     *
     * @return 单条客户端连接允许同时处理的最大在途请求数
     */
    public int maxRequestsPerConnection() {
        return maxRequestsPerConnection;
    }

    /**
     * 返回单个服务端实例接受的最大客户端连接数。
     *
     * @return 单个服务端实例接受的最大客户端连接数
     */
    public int maxServerConnections() {
        return maxServerConnections;
    }

    /**
     * 返回单个客户端实例维护的最大服务端连接数。
     *
     * @return 单个客户端实例维护的最大服务端连接数
     */
    public int maxClientConnections() {
        return maxClientConnections;
    }

    /**
     * 返回服务端监听 Socket 的连接积压队列容量。
     *
     * @return 服务端监听 Socket 的连接积压队列容量
     */
    public int backlog() {
        return backlog;
    }

    /**
     * 返回建立 TCP 连接的最长等待时间，单位为毫秒。
     *
     * @return 建立 TCP 连接的最长等待时间，单位为毫秒
     */
    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    /**
     * 返回单次网络写入的最长等待时间，单位为毫秒。
     *
     * @return 单次网络写入的最长等待时间，单位为毫秒
     */
    public int writeTimeoutMillis() {
        return writeTimeoutMillis;
    }

    /**
     * 返回一次完整 RPC 调用的总时间预算，单位为毫秒。
     *
     * @return 一次完整 RPC 调用的总时间预算，单位为毫秒
     */
    public int requestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    /**
     * 返回启用 TLS 时完成握手的最长等待时间，单位为毫秒。
     *
     * @return 启用 TLS 时完成握手的最长等待时间，单位为毫秒
     */
    public int handshakeTimeoutMillis() {
        return handshakeTimeoutMillis;
    }

    /**
     * 返回连接无写入时发送心跳的间隔，单位为毫秒。
     *
     * @return 连接无写入时发送心跳的间隔，单位为毫秒
     */
    public int heartbeatIntervalMillis() {
        return heartbeatIntervalMillis;
    }

    /**
     * 返回连接持续未读取到数据时判定失活的时间，单位为毫秒。
     *
     * @return 连接持续未读取到数据时判定失活的时间，单位为毫秒
     */
    public int readIdleTimeoutMillis() {
        return readIdleTimeoutMillis;
    }

    /**
     * 返回优雅停机时等待业务在途请求排空的最长时间，单位为毫秒。
     *
     * @return 优雅停机时等待业务在途请求排空的最长时间，单位为毫秒
     */
    public int drainTimeoutMillis() {
        return drainTimeoutMillis;
    }

    /**
     * 返回排空后关闭线程、连接等资源的最长时间，单位为毫秒。
     *
     * @return 排空后关闭线程、连接等资源的最长时间，单位为毫秒
     */
    public int shutdownTimeoutMillis() {
        return shutdownTimeoutMillis;
    }

    /**
     * 返回单个 RPC 消息体允许的最大字节数，不包含十九字节固定头。
     *
     * @return 单个 RPC 消息体允许的最大字节数，不包含十九字节固定头
     */
    public int maxMessageLength() {
        return maxMessageLength;
    }

    /**
     * 返回Channel 写缓冲区进入可写恢复状态的低水位字节数。
     *
     * @return Channel 写缓冲区进入可写恢复状态的低水位字节数
     */
    public int writeLowWaterMark() {
        return writeLowWaterMark;
    }

    /**
     * 返回Channel 写缓冲区进入不可写状态的高水位字节数。
     *
     * @return Channel 写缓冲区进入不可写状态的高水位字节数
     */
    public int writeHighWaterMark() {
        return writeHighWaterMark;
    }

    /**
     * 返回单条 Channel 可以预留的最大待写报文字节数。
     *
     * @return 单条 Channel 可以预留的最大待写报文字节数
     */
    public int maxChannelWriteBytes() {
        return maxChannelWriteBytes;
    }

    /**
     * 返回单个客户端或服务端实例可持有的最大逻辑报文字节预算。
     *
     * @return 单个客户端或服务端实例可持有的最大逻辑报文字节预算
     */
    public int maxBufferedBytes() {
        return maxBufferedBytes;
    }

    /**
     * 返回单个服务端实例每秒允许处理的请求数。
     *
     * @return 单个服务端实例每秒允许处理的请求数
     */
    public int requestsPerSecond() {
        return requestsPerSecond;
    }

    /**
     * 返回服务端限流令牌桶在空闲时最多积累的令牌数。
     *
     * @return 服务端限流令牌桶在空闲时最多积累的令牌数
     */
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

        /**
         * 设置服务端监听连接的 boss EventLoop 线程数。
         *
         * @param value 服务端监听连接的 boss EventLoop 线程数，必须大于零
         * @return 当前构建器
         */
        public Builder serverBossThreads(int value) {
            serverBossThreads = value;
            return this;
        }

        /**
         * 设置服务端已建立连接的 I/O EventLoop 线程数。
         *
         * @param value 服务端已建立连接的 I/O EventLoop 线程数，必须大于零
         * @return 当前构建器
         */
        public Builder serverWorkerThreads(int value) {
            serverWorkerThreads = value;
            return this;
        }

        /**
         * 设置客户端所有连接共享的 I/O EventLoop 线程数。
         *
         * @param value 客户端所有连接共享的 I/O EventLoop 线程数，必须大于零
         * @return 当前构建器
         */
        public Builder clientIoThreads(int value) {
            clientIoThreads = value;
            return this;
        }

        /**
         * 设置服务端执行业务方法的固定线程池线程数。
         *
         * @param value 服务端执行业务方法的固定线程池线程数，必须大于零
         * @return 当前构建器
         */
        public Builder businessThreads(int value) {
            businessThreads = value;
            return this;
        }

        /**
         * 设置客户端和服务端执行编解码转换的固定线程池线程数。
         *
         * @param value 客户端和服务端执行编解码转换的固定线程池线程数，必须大于零
         * @return 当前构建器
         */
        public Builder codecThreads(int value) {
            codecThreads = value;
            return this;
        }

        /**
         * 设置客户端完成调用结果及用户回调的固定线程池线程数。
         *
         * @param value 客户端完成调用结果及用户回调的固定线程池线程数，必须大于零
         * @return 当前构建器
         */
        public Builder completionThreads(int value) {
            completionThreads = value;
            return this;
        }

        /**
         * 设置服务端业务线程池的有界等待队列容量。
         *
         * @param value 服务端业务线程池的有界等待队列容量，必须大于零
         * @return 当前构建器
         */
        public Builder businessQueueCapacity(int value) {
            businessQueueCapacity = value;
            return this;
        }

        /**
         * 设置编解码线程池的有界等待队列容量。
         *
         * @param value 编解码线程池的有界等待队列容量，必须大于零
         * @return 当前构建器
         */
        public Builder codecQueueCapacity(int value) {
            codecQueueCapacity = value;
            return this;
        }

        /**
         * 设置单个客户端实例允许登记的最大在途请求数。
         *
         * @param value 单个客户端实例允许登记的最大在途请求数，必须大于零
         * @return 当前构建器
         */
        public Builder maxPendingRequests(int value) {
            maxPendingRequests = value;
            return this;
        }

        /**
         * 设置单条客户端连接允许同时处理的最大在途请求数。
         *
         * @param value 单条客户端连接允许同时处理的最大在途请求数，必须大于零
         * @return 当前构建器
         */
        public Builder maxRequestsPerConnection(int value) {
            maxRequestsPerConnection = value;
            return this;
        }

        /**
         * 设置单个服务端实例接受的最大客户端连接数。
         *
         * @param value 单个服务端实例接受的最大客户端连接数，必须大于零
         * @return 当前构建器
         */
        public Builder maxServerConnections(int value) {
            maxServerConnections = value;
            return this;
        }

        /**
         * 设置单个客户端实例维护的最大服务端连接数。
         *
         * @param value 单个客户端实例维护的最大服务端连接数，必须大于零
         * @return 当前构建器
         */
        public Builder maxClientConnections(int value) {
            maxClientConnections = value;
            return this;
        }

        /**
         * 设置服务端监听 Socket 的连接积压队列容量。
         *
         * @param value 服务端监听 Socket 的连接积压队列容量，必须大于零
         * @return 当前构建器
         */
        public Builder backlog(int value) {
            backlog = value;
            return this;
        }

        /**
         * 设置建立 TCP 连接的最长等待时间，单位为毫秒。
         *
         * @param value 建立 TCP 连接的最长等待时间，单位为毫秒，必须大于零
         * @return 当前构建器
         */
        public Builder connectTimeoutMillis(int value) {
            connectTimeoutMillis = value;
            return this;
        }

        /**
         * 设置单次网络写入的最长等待时间，单位为毫秒。
         *
         * @param value 单次网络写入的最长等待时间，单位为毫秒，必须大于零
         * @return 当前构建器
         */
        public Builder writeTimeoutMillis(int value) {
            writeTimeoutMillis = value;
            return this;
        }

        /**
         * 设置一次完整 RPC 调用的总时间预算，单位为毫秒。
         *
         * @param value 一次完整 RPC 调用的总时间预算，单位为毫秒，必须大于零
         * @return 当前构建器
         */
        public Builder requestTimeoutMillis(int value) {
            requestTimeoutMillis = value;
            return this;
        }

        /**
         * 设置启用 TLS 时完成握手的最长等待时间，单位为毫秒。
         *
         * @param value 启用 TLS 时完成握手的最长等待时间，单位为毫秒，必须大于零
         * @return 当前构建器
         */
        public Builder handshakeTimeoutMillis(int value) {
            handshakeTimeoutMillis = value;
            return this;
        }

        /**
         * 设置连接无写入时发送心跳的间隔，单位为毫秒。
         *
         * @param value 连接无写入时发送心跳的间隔，单位为毫秒，必须大于零
         * @return 当前构建器
         */
        public Builder heartbeatIntervalMillis(int value) {
            heartbeatIntervalMillis = value;
            return this;
        }

        /**
         * 设置连接持续未读取到数据时判定失活的时间，单位为毫秒。
         *
         * @param value 连接持续未读取到数据时判定失活的时间，单位为毫秒，必须大于零
         * @return 当前构建器
         */
        public Builder readIdleTimeoutMillis(int value) {
            readIdleTimeoutMillis = value;
            return this;
        }

        /**
         * 设置优雅停机时等待业务在途请求排空的最长时间，单位为毫秒。
         *
         * @param value 优雅停机时等待业务在途请求排空的最长时间，单位为毫秒，必须大于零
         * @return 当前构建器
         */
        public Builder drainTimeoutMillis(int value) {
            drainTimeoutMillis = value;
            return this;
        }

        /**
         * 设置排空后关闭线程、连接等资源的最长时间，单位为毫秒。
         *
         * @param value 排空后关闭线程、连接等资源的最长时间，单位为毫秒，必须大于零
         * @return 当前构建器
         */
        public Builder shutdownTimeoutMillis(int value) {
            shutdownTimeoutMillis = value;
            return this;
        }

        /**
         * 设置单个 RPC 消息体允许的最大字节数，不包含十九字节固定头。
         *
         * @param value 单个 RPC 消息体允许的最大字节数，不包含十九字节固定头，必须大于零
         * @return 当前构建器
         */
        public Builder maxMessageLength(int value) {
            maxMessageLength = value;
            return this;
        }

        /**
         * 设置Channel 写缓冲区进入可写恢复状态的低水位字节数。
         *
         * @param value Channel 写缓冲区进入可写恢复状态的低水位字节数，必须大于零
         * @return 当前构建器
         */
        public Builder writeLowWaterMark(int value) {
            writeLowWaterMark = value;
            return this;
        }

        /**
         * 设置Channel 写缓冲区进入不可写状态的高水位字节数。
         *
         * @param value Channel 写缓冲区进入不可写状态的高水位字节数，必须大于零
         * @return 当前构建器
         */
        public Builder writeHighWaterMark(int value) {
            writeHighWaterMark = value;
            return this;
        }

        /**
         * 设置单条 Channel 可以预留的最大待写报文字节数。
         *
         * @param value 单条 Channel 可以预留的最大待写报文字节数，必须大于零
         * @return 当前构建器
         */
        public Builder maxChannelWriteBytes(int value) {
            maxChannelWriteBytes = value;
            return this;
        }

        /**
         * 设置单个客户端或服务端实例可持有的最大逻辑报文字节预算。
         *
         * @param value 单个客户端或服务端实例可持有的最大逻辑报文字节预算，必须大于零
         * @return 当前构建器
         */
        public Builder maxBufferedBytes(int value) {
            maxBufferedBytes = value;
            return this;
        }

        /**
         * 设置单个服务端实例每秒允许处理的请求数。
         *
         * @param value 单个服务端实例每秒允许处理的请求数，必须大于零
         * @return 当前构建器
         */
        public Builder requestsPerSecond(int value) {
            requestsPerSecond = value;
            return this;
        }

        /**
         * 设置服务端限流令牌桶在空闲时最多积累的令牌数。
         *
         * @param value 服务端限流令牌桶在空闲时最多积累的令牌数，必须大于零
         * @return 当前构建器
         */
        public Builder burstCapacity(int value) {
            burstCapacity = value;
            return this;
        }

        /**
         * 校验并构建不可变生产参数。
         *
         * @return 生产参数实例
         * @throws IllegalArgumentException 任一参数非正或参数组合不合法时抛出
         */
        public RpcConfig build() {
            return new RpcConfig(this);
        }
    }
}
