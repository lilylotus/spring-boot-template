package org.example.simple.util.echo;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * TCP echo 服务端压测工具入口，基于 Netty 以可控速率建立大量长连接，在每个连接上循环执行
 * "发送随机字符串 → 收齐并逐字节校验回显 → 间隔 → 再发送"，周期输出在线连接数、QPS、延迟等统计，
 * 用于评估 echo 服务端可承载的最大客户端数。
 * <p>
 * 命令行用法（参数均为 {@code --key=value}，全部可选）：
 * <pre>
 * gradlew echoLoadTest --args="--host=10.10.10.206 --port=19090 --clients=50000 --duration=0"
 * </pre>
 * 代码调用：{@link #run(EchoLoadTestConfig)} 阻塞运行到结束并返回报告；{@link #start(EchoLoadTestConfig)}
 * 非阻塞启动并返回 {@link EchoLoadTestSession}，可随时 {@code snapshot()} 与 {@code stop()}。
 * <p>
 * Linux 下自动使用 Netty native epoll 传输，其余平台使用 NIO。压测海量连接前需调大压测端的
 * 文件描述符上限与本地端口范围，详见 {@code docs/EchoServerUtils使用说明.md}。
 */
public final class EchoServerUtils {

    /** 命令行用法说明。 */
    static final String USAGE = """
            用法：EchoServerUtils [--key=value ...]
              --host=<地址>                    目标地址，默认 10.10.10.206
              --port=<端口>                    目标端口，默认 19090
              --clients=<数量>                 目标连接数，默认 1000
              --duration=<秒>                  运行时长，0 表示一直运行直到 Ctrl+C，默认 60
              --connect-rate=<每秒>            每秒最多发起的建连数，默认 1000
              --max-pending-connects=<数量>    同时建连中的最大连接数，默认 1000
              --interval-ms=<毫秒>             收齐回显后到下次发送的间隔，0 表示收到即发，默认 1000
              --min-message-size=<字节>        每次请求随机字符串的最小长度，默认 16
              --max-message-size=<字节>        每次请求随机字符串的最大长度，默认 128
              --connect-timeout-ms=<毫秒>      建连超时，默认 5000
              --response-timeout-ms=<毫秒>     回显超时，默认 5000
              --local-addresses=<ip1,ip2>      本地绑定 IP，逗号分隔，按连接轮询使用，默认由系统选择
              --io-threads=<数量>              IO 线程数，默认 CPU 核数
              --report-interval-seconds=<秒>   统计输出间隔，默认 1
              --help                           打印本说明
            """;

    /** 工具类禁止实例化。 */
    private EchoServerUtils() {
    }

    /**
     * 命令行入口。参数错误时打印用法并以退出码 {@code 2} 结束；进程收到终止信号（如 Ctrl+C）时
     * 停止压测并输出汇总。
     *
     * @param args {@code --key=value} 形式的参数
     */
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--help")) {
            System.out.println(USAGE);
            return;
        }
        EchoLoadTestConfig config;
        try {
            config = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误：" + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        EchoLoadTestSession session = start(config);
        Thread hook = new Thread(session::stop, "echo-load-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        session.awaitTermination();
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // JVM 已在关闭过程中（由 Ctrl+C 触发），钩子会自行完成停止
        }
    }

    /**
     * 解析命令行参数，未传入的参数使用默认值。
     *
     * @param args {@code --key=value} 形式的参数
     * @return 校验通过的配置
     * @throws IllegalArgumentException 参数格式错误、参数名未知或取值非法
     */
    public static EchoLoadTestConfig parseArgs(String... args) {
        Objects.requireNonNull(args, "参数数组不能为 null");
        EchoLoadTestConfig.Builder builder = EchoLoadTestConfig.builder();
        for (String arg : args) {
            if (arg == null || !arg.startsWith("--") || arg.indexOf('=') < 0) {
                throw new IllegalArgumentException("参数格式应为 --key=value：" + arg);
            }
            int eq = arg.indexOf('=');
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1).trim();
            switch (key) {
                case "host" -> builder.host(value);
                case "port" -> builder.port(parseInt(key, value));
                case "clients" -> builder.clients(parseInt(key, value));
                case "duration" -> builder.durationSeconds(parseLong(key, value));
                case "connect-rate" -> builder.connectRate(parseInt(key, value));
                case "max-pending-connects" -> builder.maxPendingConnects(parseInt(key, value));
                case "interval-ms" -> builder.intervalMillis(parseLong(key, value));
                case "min-message-size" -> builder.minMessageSize(parseInt(key, value));
                case "max-message-size" -> builder.maxMessageSize(parseInt(key, value));
                case "connect-timeout-ms" -> builder.connectTimeoutMillis(parseInt(key, value));
                case "response-timeout-ms" -> builder.responseTimeoutMillis(parseLong(key, value));
                case "local-addresses" -> builder.localAddresses(parseList(value));
                case "io-threads" -> builder.ioThreads(parseInt(key, value));
                case "report-interval-seconds" -> builder.reportIntervalSeconds(parseLong(key, value));
                default -> throw new IllegalArgumentException("未知参数：--" + key);
            }
        }
        return builder.build();
    }

    /**
     * 非阻塞启动一次压测会话。
     *
     * @param config 压测配置
     * @return 已开始运行的会话
     * @throws IllegalArgumentException 目标地址或本地绑定地址无法解析
     */
    public static EchoLoadTestSession start(EchoLoadTestConfig config) {
        Objects.requireNonNull(config, "压测配置不能为 null");
        EchoLoadTestSession session = new EchoLoadTestSession(config);
        session.begin();
        return session;
    }

    /**
     * 阻塞运行一次压测直到结束并返回最终报告。一直运行模式（{@code duration=0}）下需由其他线程调用
     * 会话的 {@code stop()}，因此该模式建议使用 {@link #start(EchoLoadTestConfig)}。
     *
     * @param config 压测配置
     * @return 最终统计报告
     */
    public static EchoLoadReport run(EchoLoadTestConfig config) {
        return start(config).awaitTermination();
    }

    /** 解析 int 参数。 */
    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 --" + key + " 不是有效整数：" + value, e);
        }
    }

    /** 解析 long 参数。 */
    private static long parseLong(String key, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 --" + key + " 不是有效整数：" + value, e);
        }
    }

    /** 解析逗号分隔列表，空字符串表示空列表。 */
    private static List<String> parseList(String value) {
        if (value.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).toList();
    }
}
