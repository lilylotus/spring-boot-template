## Why

需要压测一个由 C++ 基于 epoll（NIO）实现的 echo 服务端，确认其能稳定承载的最大客户端连接数，以及在大量连接下的回显延迟与吞吐。项目中已有 Netty 依赖，但没有面向任意 TCP echo 服务端的压测工具；临时脚本难以同时控制建连速率、维持海量长连接、校验回显内容并持续输出统计。

## What Changes

- 新增压测工具入口 `org.example.simple.util.echo.EchoServerUtils`，基于 Netty 客户端实现，可通过 `main` 命令行参数运行，也可在代码中以 API 方式调用。
- 默认目标地址 `10.10.10.206:19090`，可通过参数覆盖。
- 支持配置：
  - 客户端（连接）数量；
  - 运行模式：按指定时长运行后自动停止，或一直运行直到手动停止（Ctrl+C / 调用 `stop()`）；
  - 建连速率与最大并发建连数（逐步爬坡，避免瞬时 SYN 洪峰把服务端 backlog 打满而误判上限）；
  - 每个连接的请求间隔、请求报文长度范围（默认 16～128 字节）、连接超时、回显超时；
  - 可选的多个本地绑定 IP（突破单 IP 约 2.8 万个临时端口的限制）；
  - IO 线程数。
- 每个连接采用"发送 → 等待完整回显 → 间隔 → 再发送"的 ping-pong 模式；每次请求发送长度在 `[min-message-size, max-message-size]`（默认 `[16, 128]`）内均匀随机、内容为随机字母数字的字符串，并逐字节校验回显内容。
- 每秒输出一行统计：目标连接数、已建立/建连中/建连失败/被断开连接数、请求完成数与 QPS、回显超时数、内容不一致数、平均与最大延迟、收发字节数；结束时输出汇总报告（含峰值同时在线连接数）。
- Linux 下自动使用 Netty native epoll 传输，其余平台使用 NIO。
- 新增 Gradle 任务 `echoLoadTest` 便于直接运行；新增使用说明文档。

## Capabilities

### New Capabilities

- `echo-server-load-test`：定义 echo 服务端压测工具的配置项与默认值、建连爬坡行为、定时/持续两种运行模式、ping-pong 请求与回显校验、统计输出与停止行为。

### Modified Capabilities

无。

## Impact

- 新增 `src/main/java/org/example/simple/util/echo/` 下的 `EchoServerUtils`、`EchoLoadTestConfig`、`EchoLoadTestSession`、`EchoClientHandler`、`EchoLoadStatistics`、`EchoLoadReport` 等类。
- 新增 `src/test/java/org/example/simple/util/echo/` 下的测试（测试内启动本地 Netty echo 服务端，不依赖 `10.10.10.206`）。
- `build.gradle` 新增 `echoLoadTest`（`JavaExec`）任务；不新增依赖（复用已有 `netty-all`、`logback`）。
- 新增 `docs/EchoServerUtils使用说明.md`，并在 `README.md` 增加入口链接。
