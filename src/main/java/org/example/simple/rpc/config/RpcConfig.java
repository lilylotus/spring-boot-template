package org.example.simple.rpc.config;

import io.netty.channel.WriteBufferWaterMark;

/** 不可变的 RPC 生产参数；所有线程数量均为固定值，可通过构建器手动覆盖。 */
public final class RpcConfig {
    private final int serverBossThreads;
    private final int serverWorkerThreads;
    private final int clientIoThreads;
    private final int businessThreads;
    private final int codecThreads;
    private final int completionThreads;
    private final int businessQueueCapacity;
    private final int codecQueueCapacity;
    private final int maxPendingRequests;
    private final int maxRequestsPerConnection;
    private final int maxServerConnections;
    private final int maxClientConnections;
    private final int backlog;
    private final int connectTimeoutMillis;
    private final int writeTimeoutMillis;
    private final int requestTimeoutMillis;
    private final int handshakeTimeoutMillis;
    private final int heartbeatIntervalMillis;
    private final int readIdleTimeoutMillis;
    private final int drainTimeoutMillis;
    private final int shutdownTimeoutMillis;
    private final int maxMessageLength;
    private final int writeLowWaterMark;
    private final int writeHighWaterMark;
    private final int maxChannelWriteBytes;
    private final int maxBufferedBytes;
    private final int requestsPerSecond;
    private final int burstCapacity;

    private RpcConfig(Builder builder) {
        this.serverBossThreads = positive(builder.serverBossThreads, "serverBossThreads");
        this.serverWorkerThreads = positive(builder.serverWorkerThreads, "serverWorkerThreads");
        this.clientIoThreads = positive(builder.clientIoThreads, "clientIoThreads");
        this.businessThreads = positive(builder.businessThreads, "businessThreads");
        this.codecThreads = positive(builder.codecThreads, "codecThreads");
        this.completionThreads = positive(builder.completionThreads, "completionThreads");
        this.businessQueueCapacity = positive(builder.businessQueueCapacity, "businessQueueCapacity");
        this.codecQueueCapacity = positive(builder.codecQueueCapacity, "codecQueueCapacity");
        this.maxPendingRequests = positive(builder.maxPendingRequests, "maxPendingRequests");
        this.maxRequestsPerConnection = positive(builder.maxRequestsPerConnection, "maxRequestsPerConnection");
        this.maxServerConnections = positive(builder.maxServerConnections, "maxServerConnections");
        this.maxClientConnections = positive(builder.maxClientConnections, "maxClientConnections");
        this.backlog = positive(builder.backlog, "backlog");
        this.connectTimeoutMillis = positive(builder.connectTimeoutMillis, "connectTimeoutMillis");
        this.writeTimeoutMillis = positive(builder.writeTimeoutMillis, "writeTimeoutMillis");
        this.requestTimeoutMillis = positive(builder.requestTimeoutMillis, "requestTimeoutMillis");
        this.handshakeTimeoutMillis = positive(builder.handshakeTimeoutMillis, "handshakeTimeoutMillis");
        this.heartbeatIntervalMillis = positive(builder.heartbeatIntervalMillis, "heartbeatIntervalMillis");
        this.readIdleTimeoutMillis = positive(builder.readIdleTimeoutMillis, "readIdleTimeoutMillis");
        this.drainTimeoutMillis = positive(builder.drainTimeoutMillis, "drainTimeoutMillis");
        this.shutdownTimeoutMillis = positive(builder.shutdownTimeoutMillis, "shutdownTimeoutMillis");
        this.maxMessageLength = positive(builder.maxMessageLength, "maxMessageLength");
        this.writeLowWaterMark = positive(builder.writeLowWaterMark, "writeLowWaterMark");
        this.writeHighWaterMark = positive(builder.writeHighWaterMark, "writeHighWaterMark");
        this.maxChannelWriteBytes = positive(builder.maxChannelWriteBytes, "maxChannelWriteBytes");
        this.maxBufferedBytes = positive(builder.maxBufferedBytes, "maxBufferedBytes");
        this.requestsPerSecond = positive(builder.requestsPerSecond, "requestsPerSecond");
        this.burstCapacity = positive(builder.burstCapacity, "burstCapacity");
        if (writeLowWaterMark >= writeHighWaterMark || writeHighWaterMark > maxChannelWriteBytes
            || maxMessageLength > Integer.MAX_VALUE - 19
            || maxChannelWriteBytes < (long) maxMessageLength + 19
            || maxBufferedBytes < (long) maxMessageLength + 19
            || heartbeatIntervalMillis >= readIdleTimeoutMillis) {
            throw new IllegalArgumentException("RPC 参数组合不合法");
        }
        Math.addExact(businessThreads, businessQueueCapacity);
    }

    public static Builder builder() { return new Builder(); }
    public static RpcConfig defaults() { return builder().build(); }
    public WriteBufferWaterMark waterMark() {
        return new WriteBufferWaterMark(writeLowWaterMark, writeHighWaterMark);
    }

    private static int positive(int value, String name) {
        if (value <= 0) { throw new IllegalArgumentException(name + "必须大于零"); }
        return value;
    }
    public int serverBossThreads() { return serverBossThreads; }
    public int serverWorkerThreads() { return serverWorkerThreads; }
    public int clientIoThreads() { return clientIoThreads; }
    public int businessThreads() { return businessThreads; }
    public int codecThreads() { return codecThreads; }
    public int completionThreads() { return completionThreads; }
    public int businessQueueCapacity() { return businessQueueCapacity; }
    public int codecQueueCapacity() { return codecQueueCapacity; }
    public int maxPendingRequests() { return maxPendingRequests; }
    public int maxRequestsPerConnection() { return maxRequestsPerConnection; }
    public int maxServerConnections() { return maxServerConnections; }
    public int maxClientConnections() { return maxClientConnections; }
    public int backlog() { return backlog; }
    public int connectTimeoutMillis() { return connectTimeoutMillis; }
    public int writeTimeoutMillis() { return writeTimeoutMillis; }
    public int requestTimeoutMillis() { return requestTimeoutMillis; }
    public int handshakeTimeoutMillis() { return handshakeTimeoutMillis; }
    public int heartbeatIntervalMillis() { return heartbeatIntervalMillis; }
    public int readIdleTimeoutMillis() { return readIdleTimeoutMillis; }
    public int drainTimeoutMillis() { return drainTimeoutMillis; }
    public int shutdownTimeoutMillis() { return shutdownTimeoutMillis; }
    public int maxMessageLength() { return maxMessageLength; }
    public int writeLowWaterMark() { return writeLowWaterMark; }
    public int writeHighWaterMark() { return writeHighWaterMark; }
    public int maxChannelWriteBytes() { return maxChannelWriteBytes; }
    public int maxBufferedBytes() { return maxBufferedBytes; }
    public int requestsPerSecond() { return requestsPerSecond; }
    public int burstCapacity() { return burstCapacity; }

    /** 显式配置，零和负值不会触发自动推导。 */
    public static final class Builder {
        private int serverBossThreads = 1;
        private int serverWorkerThreads = 4;
        private int clientIoThreads = 2;
        private int businessThreads = 8;
        private int codecThreads = 2;
        private int completionThreads = 2;
        private int businessQueueCapacity = 256;
        private int codecQueueCapacity = 256;
        private int maxPendingRequests = 1024;
        private int maxRequestsPerConnection = 128;
        private int maxServerConnections = 1024;
        private int maxClientConnections = 128;
        private int backlog = 1024;
        private int connectTimeoutMillis = 1000;
        private int writeTimeoutMillis = 1000;
        private int requestTimeoutMillis = 5000;
        private int handshakeTimeoutMillis = 3000;
        private int heartbeatIntervalMillis = 15000;
        private int readIdleTimeoutMillis = 45000;
        private int drainTimeoutMillis = 30000;
        private int shutdownTimeoutMillis = 5000;
        private int maxMessageLength = 8388608;
        private int writeLowWaterMark = 32768;
        private int writeHighWaterMark = 65536;
        private int maxChannelWriteBytes = 16777216;
        private int maxBufferedBytes = 67108864;
        private int requestsPerSecond = 1000;
        private int burstCapacity = 200;
        public Builder serverBossThreads(int value) { serverBossThreads = value; return this; }
        public Builder serverWorkerThreads(int value) { serverWorkerThreads = value; return this; }
        public Builder clientIoThreads(int value) { clientIoThreads = value; return this; }
        public Builder businessThreads(int value) { businessThreads = value; return this; }
        public Builder codecThreads(int value) { codecThreads = value; return this; }
        public Builder completionThreads(int value) { completionThreads = value; return this; }
        public Builder businessQueueCapacity(int value) { businessQueueCapacity = value; return this; }
        public Builder codecQueueCapacity(int value) { codecQueueCapacity = value; return this; }
        public Builder maxPendingRequests(int value) { maxPendingRequests = value; return this; }
        public Builder maxRequestsPerConnection(int value) { maxRequestsPerConnection = value; return this; }
        public Builder maxServerConnections(int value) { maxServerConnections = value; return this; }
        public Builder maxClientConnections(int value) { maxClientConnections = value; return this; }
        public Builder backlog(int value) { backlog = value; return this; }
        public Builder connectTimeoutMillis(int value) { connectTimeoutMillis = value; return this; }
        public Builder writeTimeoutMillis(int value) { writeTimeoutMillis = value; return this; }
        public Builder requestTimeoutMillis(int value) { requestTimeoutMillis = value; return this; }
        public Builder handshakeTimeoutMillis(int value) { handshakeTimeoutMillis = value; return this; }
        public Builder heartbeatIntervalMillis(int value) { heartbeatIntervalMillis = value; return this; }
        public Builder readIdleTimeoutMillis(int value) { readIdleTimeoutMillis = value; return this; }
        public Builder drainTimeoutMillis(int value) { drainTimeoutMillis = value; return this; }
        public Builder shutdownTimeoutMillis(int value) { shutdownTimeoutMillis = value; return this; }
        public Builder maxMessageLength(int value) { maxMessageLength = value; return this; }
        public Builder writeLowWaterMark(int value) { writeLowWaterMark = value; return this; }
        public Builder writeHighWaterMark(int value) { writeHighWaterMark = value; return this; }
        public Builder maxChannelWriteBytes(int value) { maxChannelWriteBytes = value; return this; }
        public Builder maxBufferedBytes(int value) { maxBufferedBytes = value; return this; }
        public Builder requestsPerSecond(int value) { requestsPerSecond = value; return this; }
        public Builder burstCapacity(int value) { burstCapacity = value; return this; }
        public RpcConfig build() { return new RpcConfig(this); }
    }
}
