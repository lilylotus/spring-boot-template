# EchoServerUtils 使用说明

`org.example.simple.util.echo.EchoServerUtils` 是基于 Netty 的 TCP echo 服务端压测工具，用于评估 echo 服务端（如 C++ epoll 实现）可承载的最大客户端连接数，以及大量连接在线时的回显延迟与吞吐。

## 工作方式

1. 按 `--connect-rate`（每秒建连数）与 `--max-pending-connects`（同时建连中的上限）逐步爬坡，直到发起 `--clients` 个连接，避免瞬时 SYN 洪峰打满服务端 backlog 导致误判。
2. 每个连接建立后立即发送一个随机字符串：长度在 `--min-message-size` 与 `--max-message-size` 之间（默认 16～128 字节）均匀随机，字符取自 `0-9A-Za-z`，每次请求重新生成。收齐等长回显并逐字节校验后，间隔 `--interval-ms` 再发送下一次（ping-pong 模式）。
3. 回显内容不一致、多余字节或超过 `--response-timeout-ms` 未收齐时，计数并由工具主动关闭该连接。
4. 建连失败不重试，被服务端断开的连接不重连，均计入统计。
5. 每隔 `--report-interval-seconds` 秒输出一行统计；按 `--duration` 到期，或 `--duration=0` 时按 Ctrl+C 停止，停止时输出汇总。
6. Linux 下自动使用 Netty native epoll 传输，其余平台使用 NIO，启动日志会打印实际传输类型。

## 参数

| 参数 | 含义 | 默认值 |
| --- | --- | --- |
| `--host` | 目标地址 | `10.10.10.206` |
| `--port` | 目标端口 | `19090` |
| `--clients` | 目标连接数 | `1000` |
| `--duration` | 运行时长（秒），从开始建连起算；`0` 表示一直运行直到 Ctrl+C | `60` |
| `--connect-rate` | 每秒最多发起的建连数 | `1000` |
| `--max-pending-connects` | 同时处于建连中的最大连接数 | `1000` |
| `--interval-ms` | 收齐回显后到下次发送的间隔；`0` 表示收到即发 | `1000` |
| `--min-message-size` | 每次请求随机字符串的最小长度（字节） | `16` |
| `--max-message-size` | 每次请求随机字符串的最大长度（字节），不得小于最小长度；两者相等即为固定长度 | `128` |
| `--connect-timeout-ms` | 建连超时 | `5000` |
| `--response-timeout-ms` | 回显超时 | `5000` |
| `--local-addresses` | 本地绑定 IP，逗号分隔，按连接轮询使用 | 系统选择 |
| `--io-threads` | IO 线程数 | CPU 核数 |
| `--report-interval-seconds` | 统计输出间隔（秒） | `1` |
| `--help` | 打印用法 | - |

参数名未知、格式不是 `--key=value` 或取值非法时，打印用法并以退出码 `2` 结束。

## 运行示例

```bash
# 默认配置：连接 10.10.10.206:19090，1000 个连接，运行 60 秒
./gradlew echoLoadTest

# 5 万连接，一直运行，Ctrl+C 停止并输出汇总
./gradlew echoLoadTest --args="--clients=50000 --duration=0"

# 10 万连接，绑定 4 个本地 IP 突破单 IP 临时端口限制，每 5 秒发一次
./gradlew echoLoadTest --args="--clients=100000 --duration=600 --interval-ms=5000 \
  --local-addresses=10.10.10.101,10.10.10.102,10.10.10.103,10.10.10.104"

# 测吞吐：少量连接、收到即发、固定 1KB 随机报文
./gradlew echoLoadTest --args="--clients=200 --interval-ms=0 --min-message-size=1024 --max-message-size=1024 --duration=30"
```

在代码中调用：

```java
EchoLoadTestConfig config = EchoLoadTestConfig.builder()
        .clients(20000)
        .durationSeconds(0)
        .build();
EchoLoadTestSession session = EchoServerUtils.start(config);
EchoLoadReport running = session.snapshot();   // 运行中查询
EchoLoadReport report = session.stop();        // 停止并返回最终报告，幂等

// 定时模式可直接阻塞运行
EchoLoadReport finished = EchoServerUtils.run(EchoLoadTestConfig.builder().durationSeconds(120).build());
```

## 统计字段

| 字段 | 含义 |
| --- | --- |
| 在线 | 当前已建立的连接数 / 目标连接数 |
| 峰值 | 运行期间同时在线的最大连接数，判断服务端承载上限的主要指标 |
| 建连中 | 已发起、尚未返回结果的连接数 |
| 已发起 | 已发起的建连总数 |
| 建连失败 | 拒绝、超时等失败的建连数 |
| 被动断开 | 非工具主动关闭的断开数（服务端关闭、连接重置等）；内容错误、回显超时和停止时的关闭不计入 |
| QPS | 统计区间内每秒完成的请求数 |
| 完成 | 累计收齐回显并校验通过的请求数 |
| 超时 | 累计回显超时数 |
| 内容错误 | 累计回显内容不一致或多余字节的次数 |
| 延迟 平均/最大 | 统计区间内已完成请求的平均与最大往返延迟 |
| 发送/接收 | 累计收发字节数 |

汇总报告中的"结束时在线连接"为停止前一刻的在线数。

## 压测端调优（Linux）

压测端自身的限制会让测得的连接数偏低，海量连接压测前请在压测机上调整：

```bash
# 进程文件描述符上限（需大于目标连接数）
ulimit -n 1048576

# 扩大本地临时端口范围，单个本地 IP 连同一目标最多约 6.4 万个连接
sysctl -w net.ipv4.ip_local_port_range="1024 65535"

# 允许复用 TIME_WAIT 端口，便于反复压测
sysctl -w net.ipv4.tcp_tw_reuse=1
```

- 单个本地 IP 连同一个 `ip:port` 的连接数受临时端口范围限制，超过时使用 `--local-addresses` 绑定多个本地 IP，或在多台压测机上分别运行后汇总。
- 每个连接约占用数 KB 内存，10 万连接需预留数百 MB 堆外内存，可通过 `JAVA_TOOL_OPTIONS=-XX:MaxDirectMemorySize=2g` 调整。
- Windows 上 NIO 能维持的连接数远低于 Linux，建议在 Linux 上压测。
- 被测服务端同样需要调大 `ulimit -n` 与 `listen` backlog（`net.core.somaxconn`），否则测得的是系统配置上限而非程序上限。

## 不支持的能力

- 断线自动重连、建连失败重试。
- UDP、TLS、自定义协议或分帧报文。
- 多机协同压测与百分位延迟（p99 等）。
- 独立 fat jar 打包。

## 被测服务端

配套的 C++ epoll echo 服务端源码与编译运行说明见 [tools/echo-server](../src/main/java/org/example/simple/util/echo/echo-server-cpp/README.md)。
