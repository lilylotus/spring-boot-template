package org.example.simple.util.echo;

/**
 * echo 压测统计快照，所有计数均为从会话开始到快照时刻的累计值（在线与建连中为瞬时值）。
 *
 * @param elapsedMillis     从开始建连到快照时刻经过的毫秒数
 * @param targetClients     目标连接数
 * @param initiated         已发起的建连数
 * @param pending           当前建连中的连接数
 * @param active            当前在线连接数
 * @param peakActive        峰值同时在线连接数
 * @param connectFailed     建连失败数
 * @param passiveClosed     被动断开数（非压测端主动关闭，如服务端关闭或连接重置）
 * @param requestsSent      已发送请求数
 * @param requestsCompleted 已收齐回显并校验通过的请求数
 * @param responseTimeouts  回显超时数
 * @param contentErrors     回显内容错误数
 * @param bytesSent         发送字节数
 * @param bytesReceived     接收字节数
 * @param latencySumNanos   已完成请求的延迟总和（纳秒）
 * @param latencyMaxNanos   已完成请求的最大延迟（纳秒）
 */
public record EchoLoadReport(
        long elapsedMillis, int targetClients,
        long initiated, long pending,
        long active, long peakActive,
        long connectFailed, long passiveClosed,
        long requestsSent, long requestsCompleted,
        long responseTimeouts, long contentErrors,
        long bytesSent, long bytesReceived,
        long latencySumNanos, long latencyMaxNanos) {

    /** 纳秒转毫秒的除数。 */
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /**
     * 全程平均延迟。
     *
     * @return 平均延迟（毫秒），无完成请求时为 {@code 0}
     */
    public double averageLatencyMillis() {
        return requestsCompleted == 0 ? 0 : latencySumNanos / NANOS_PER_MILLI / requestsCompleted;
    }

    /**
     * 全程最大延迟。
     *
     * @return 最大延迟（毫秒）
     */
    public double maxLatencyMillis() {
        return latencyMaxNanos / NANOS_PER_MILLI;
    }

    /**
     * 全程平均 QPS。
     *
     * @return 完成请求数 / 运行秒数
     */
    public double averageQps() {
        return elapsedMillis <= 0 ? 0 : requestsCompleted * 1000.0 / elapsedMillis;
    }

    /**
     * 生成一行区间统计：连接状态取当前值，QPS 与延迟取与上一快照之间的区间值。
     *
     * @param previous           上一次快照，首次输出时可传入 {@code null}
     * @param intervalMaxNanos   区间内的最大延迟（纳秒）
     * @return 中文统计行
     */
    public String toIntervalLine(EchoLoadReport previous, long intervalMaxNanos) {
        long prevElapsed = previous == null ? 0 : previous.elapsedMillis;
        long prevCompleted = previous == null ? 0 : previous.requestsCompleted;
        long prevLatencySum = previous == null ? 0 : previous.latencySumNanos;
        long deltaMillis = elapsedMillis - prevElapsed;
        long deltaCompleted = requestsCompleted - prevCompleted;
        double qps = deltaMillis <= 0 ? 0 : deltaCompleted * 1000.0 / deltaMillis;
        double avgMillis = deltaCompleted == 0
                ? 0
                : (latencySumNanos - prevLatencySum) / NANOS_PER_MILLI / deltaCompleted;
        return String.format(
                "[%ds] 在线=%d/%d 峰值=%d 建连中=%d 已发起=%d 建连失败=%d 被动断开=%d | "
                        + "QPS=%.1f 完成=%d 超时=%d 内容错误=%d | 延迟 平均=%.2fms 最大=%.2fms | "
                        + "发送=%s 接收=%s",
                elapsedMillis / 1000, active, targetClients, peakActive, pending, initiated,
                connectFailed, passiveClosed, qps, requestsCompleted, responseTimeouts, contentErrors,
                avgMillis, intervalMaxNanos / NANOS_PER_MILLI, formatBytes(bytesSent), formatBytes(bytesReceived));
    }

    /**
     * 生成多行中文汇总报告。
     *
     * @return 汇总文本
     */
    public String toSummary() {
        return String.format(
                "压测汇总：%n"
                        + "  运行时长        %.1f 秒%n"
                        + "  目标连接数      %d%n"
                        + "  已发起建连      %d%n"
                        + "  峰值在线连接    %d%n"
                        + "  结束时在线连接  %d%n"
                        + "  建连失败        %d%n"
                        + "  被动断开        %d%n"
                        + "  请求发送/完成   %d / %d%n"
                        + "  平均 QPS        %.1f%n"
                        + "  回显超时        %d%n"
                        + "  内容错误        %d%n"
                        + "  延迟 平均/最大  %.2fms / %.2fms%n"
                        + "  发送/接收字节   %s / %s",
                elapsedMillis / 1000.0, targetClients, initiated, peakActive, active, connectFailed,
                passiveClosed, requestsSent, requestsCompleted, averageQps(), responseTimeouts, contentErrors,
                averageLatencyMillis(), maxLatencyMillis(), formatBytes(bytesSent), formatBytes(bytesReceived));
    }

    /** 将字节数格式化为带单位的可读文本。 */
    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024L * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1fMB", bytes / 1024.0 / 1024);
        }
        return String.format("%.2fGB", bytes / 1024.0 / 1024 / 1024);
    }
}
