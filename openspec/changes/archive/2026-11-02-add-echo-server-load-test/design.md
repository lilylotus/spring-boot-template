## Context

被测对象是 C++ 基于 epoll 的 TCP echo 服务端：收到什么字节就原样写回，没有应用层协议与分帧。压测目标首先是"最大客户端连接数"，其次是大量连接在线时的回显延迟与吞吐。压测端使用项目已有的 `io.netty:netty-all:4.2.16.Final`。

压测端自身也有瓶颈，必须在设计中规避，否则测到的是压测端上限而不是服务端上限：

- 单个本地 IP 对同一目标 `ip:port` 的连接数受临时端口范围限制（Linux 默认 `32768-60999` 约 2.8 万，Windows 默认 `49152-65535` 约 1.6 万）。
- 进程文件描述符上限（`ulimit -n`）。
- 瞬时大量建连会造成服务端 `listen` backlog 溢出、SYN 丢弃重传，表现为连接超时，容易被误判为服务端承载上限。

## Goals / Non-Goals

**Goals:**
- 可配置连接数、建连速率、运行时长（或一直运行）、请求间隔、随机报文长度范围等，默认目标 `10.10.10.206:19090`。
- 以可控速率爬坡建连，持续保持连接在线并周期性发送请求，统计在线连接数、失败/断开数、QPS、延迟。
- 校验回显内容正确性。
- 同时支持命令行运行和代码 API 调用；API 可非阻塞启动、随时查询快照、随时停止。
- Linux 下使用 native epoll，降低压测端开销。

**Non-Goals:**
- 不实现断线自动重连（被服务端断开的连接计入"被断开"，用于判断服务端上限行为；如需要另行提出变更）。
- 不支持 UDP、TLS、自定义协议或分帧报文；只支持原始 TCP 字节回显。
- 不实现分布式多机协同压测；多机压测由使用者在多台机器上分别运行并汇总。
- 不提供百分位延迟（p99 等），只统计平均与最大延迟，避免引入 HdrHistogram 依赖。
- 不打包独立 fat jar；通过 Gradle 任务或现有类路径运行。
- 不自动修改操作系统参数（`ulimit`、`ip_local_port_range` 等），仅在文档中给出调优说明。

## Decisions

### 包与类划分

放在 `org.example.simple.util.echo`：

| 类 | 职责 |
| --- | --- |
| `EchoServerUtils` | `final` 工具入口：`main(String[])`、`start(EchoLoadTestConfig)` 非阻塞启动返回会话、`run(EchoLoadTestConfig)` 阻塞运行返回报告、`parseArgs(String[])` 解析命令行 |
| `EchoLoadTestConfig` | 不可变配置 `record`，提供 `defaults()` 与带校验的 Builder |
| `EchoLoadTestSession` | 一次压测会话：持有 EventLoopGroup、Bootstrap、爬坡调度、统计打印；提供 `snapshot()`、`stop()`、`awaitTermination()` |
| `EchoClientHandler` | 每连接一个实例，实现 ping-pong 发送、回显累积校验、延迟记录、回显超时 |
| `EchoLoadStatistics` | 基于 `LongAdder`/`AtomicLong` 的线程安全计数器，生成 `EchoLoadReport` 快照 |
| `EchoLoadReport` | 不可变统计快照 `record`，提供中文格式化输出 |

### 配置项与默认值

命令行使用 `--key=value` 形式，未知参数或非法值抛出 `IllegalArgumentException` 并打印用法。

| 参数 | 含义 | 默认值 |
| --- | --- | --- |
| `--host` | 目标地址 | `10.10.10.206` |
| `--port` | 目标端口 | `19090` |
| `--clients` | 目标连接数 | `1000` |
| `--duration` | 运行时长（秒），从开始建连起算；`0` 表示一直运行直到手动停止 | `60` |
| `--connect-rate` | 每秒最多发起的建连数 | `1000` |
| `--max-pending-connects` | 同时处于建连中的最大连接数 | `1000` |
| `--interval-ms` | 单连接收到完整回显后到下一次发送的间隔；`0` 表示收到即发 | `1000` |
| `--min-message-size` | 每次请求随机字符串的最小长度（字节） | `16` |
| `--max-message-size` | 每次请求随机字符串的最大长度（字节），不得小于最小长度；两者相等即为固定长度 | `128` |
| `--connect-timeout-ms` | 建连超时 | `5000` |
| `--response-timeout-ms` | 回显超时（发送后在此时间内未收齐视为超时） | `5000` |
| `--local-addresses` | 逗号分隔的本地绑定 IP，按轮询分配给连接；为空则由系统选择 | 空 |
| `--io-threads` | IO 线程数 | CPU 核数 |
| `--report-interval-seconds` | 统计输出间隔 | `1` |

"一直请求"用 `--duration=0` 表达，而不是单独的布尔开关，避免两个参数互相矛盾。

### 建连爬坡

使用单线程 `ScheduledExecutorService`（或某个 EventLoop 的 schedule）每 100ms 一个 tick：本 tick 可发起数量 = `min(connectRate / 10 的累积额度, maxPendingConnects - 当前建连中数, clients - 已发起数)`。建连失败（超时、拒绝）不重试，计入失败数，且不再补发——目标是观测服务端在给定建连压力下能接受多少连接。全部发起完毕后停止爬坡任务。

取舍：失败不补发使得"已建立 + 失败 = 目标数"，统计口径清晰；若要测"重试后最终能维持多少"，可再跑一轮或后续扩展。

### 传输实现

Netty 4.2 使用 `MultiThreadIoEventLoopGroup`：`Epoll.isAvailable()` 时用 `EpollIoHandler.newFactory()` + `EpollSocketChannel`，否则 `NioIoHandler.newFactory()` + `NioSocketChannel`。启动时打印实际使用的传输类型。Channel 选项：`TCP_NODELAY=true`、`SO_KEEPALIVE=true`、`CONNECT_TIMEOUT_MILLIS`、`SO_LINGER` 不设置。为降低每连接内存，接收缓冲使用 `AdaptiveRecvByteBufAllocator` 默认值，不额外放大 `SO_RCVBUF/SO_SNDBUF`。

`--local-addresses` 非空时调用 `bootstrap.connect(remote, localAddress)`，本地端口由系统分配，按连接序号轮询选择 IP。

### Ping-pong 与回显校验

- 每次发送前用 `ThreadLocalRandom` 生成一个新的随机字符串：长度在 `[minMessageSize, maxMessageSize]` 内均匀随机，字符取自 `0-9A-Za-z`（可打印 ASCII，便于抓包排查，且不含换行，避免服务端若按行处理时产生歧义）。生成的 `byte[]` 作为本次请求的期望回显保存在连接状态中，发送时 `Unpooled.wrappedBuffer` 包装，不复制，发送后不再修改。
- 取舍：每次请求分配一个 16～128 字节的小数组，相比共享固定报文多一次分配，但能避免服务端对固定内容的缓存或巧合匹配掩盖错误，且开销远小于网络往返；回显错位时不同请求内容不同也更容易被发现。
- 每连接状态：`expectedOffset`（已收到并校验的字节数）、`sendNanos`、回显超时 `ScheduledFuture`。
- `channelRead` 中逐字节与报文对应位置比较，不一致则内容错误数 +1 并关闭该连接（避免后续错位误报）；收齐本次请求长度的字节即完成一次请求：记录延迟、取消超时任务，按 `intervalMs` 调度下一次发送（`0` 则立即发送）。收到超出预期的多余字节同样视为内容错误。
- 回显超时：计数 +1 并关闭连接。超时说明服务端已无法及时服务该连接，继续在同一连接上发送会导致字节错位。
- 连接建立后立即发送第一次请求。

### 统计

计数器：已发起、建连中、已建立（当前在线）、峰值在线、建连失败、被动断开（非压测端主动关闭）、请求发送数、请求完成数、回显超时、内容错误、发送/接收字节、延迟总和与最大值。周期输出使用区间差值计算 QPS、区间平均延迟与区间最大延迟（区间最大值通过 `AtomicLong.getAndSet(0)` 取得）。输出通过 SLF4J/logback 以 `INFO` 打印一行中文统计；结束时打印汇总报告并作为 `EchoLoadReport` 返回。

### 运行与停止

- `duration > 0`：从开始建连计时，到期后执行停止流程。
- `duration = 0`：一直运行；命令行模式注册 JVM shutdown hook，Ctrl+C 时执行停止流程并打印汇总。
- 停止流程：停止爬坡与发送 → 关闭所有连接（标记为主动关闭，不计入被动断开）→ `shutdownGracefully` → 输出最终报告；`stop()` 幂等。

### Gradle 任务

`build.gradle` 新增：

```groovy
tasks.register('echoLoadTest', JavaExec) {
    group = 'application'
    description = '运行 echo 服务端压测工具，参数通过 --args 传入'
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'org.example.simple.util.echo.EchoServerUtils'
}
```

使用：`gradlew echoLoadTest --args="--clients=50000 --duration=0"`。

### 测试策略

测试中在 `127.0.0.1` 随机端口启动一个 Netty echo 服务端（另提供一个"写回错误字节"的服务端和一个"不回显"的服务端），用少量连接（如 20）和短时长（如 2 秒）验证，不访问 `10.10.10.206`。

## Risks / Trade-offs

- **压测端成为瓶颈**：单 IP 临时端口、`ulimit -n`、内存不足都会导致测得的值偏低。文档给出 Linux 调优参数（`ulimit -n`、`net.ipv4.ip_local_port_range`、`net.ipv4.tcp_tw_reuse`）并提供 `--local-addresses`；每连接内存约数 KB，10 万连接需预留数百 MB 堆外内存。
- **Windows 压测端上限低**：NIO 在 Windows 上连接数通常远低于 Linux；文档建议在 Linux 上压测。
- **服务端 TCP 分段回显**：回显可能分多次到达，按累计偏移校验已覆盖该情况。
- **大间隔下延迟样本少**：`--interval-ms` 较大时 QPS 低，延迟统计意义有限；这是以连接数为主目标时的预期行为。
- **失败不补发**：可能低估"重试后可维持的连接数"，已在 Non-Goals 与文档中说明。
