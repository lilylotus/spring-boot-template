# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/echo-server-load-test/spec.md` 的明确确认，尤其是：配置项与默认值（`clients=1000`、`duration=60`、`interval-ms=1000`、报文长度 `16-128`、`connect-rate=1000`）、`--duration=0` 表示一直运行、ping-pong 模式、建连失败不重试且断线不重连、回显错误/超时即关闭连接、仅统计平均与最大延迟、通过 Gradle `echoLoadTest` 任务运行而不打 fat jar。

## 2. 实现

- [x] 2.1 新增 `EchoLoadTestConfig` 不可变 `record`、`defaults()` 与带校验的 Builder，编写中文 Javadoc。
- [x] 2.2 在 `EchoServerUtils` 中实现 `parseArgs(String[])`：`--key=value` 解析、未知参数与非法值抛 `IllegalArgumentException`、用法说明文本。
- [x] 2.3 新增 `EchoLoadStatistics` 与 `EchoLoadReport`：线程安全计数、峰值在线、区间 QPS/平均/最大延迟、中文格式化输出。
- [x] 2.4 新增 `EchoClientHandler`：连接建立即发送、按累计偏移逐字节校验回显、完成后按间隔调度下次发送、回显超时、内容错误关闭、主动/被动关闭区分。
- [x] 2.5 新增 `EchoLoadTestSession`：epoll/NIO 传输选择、Bootstrap 配置、本地 IP 轮询绑定、100ms tick 爬坡建连、周期统计输出、定时停止、幂等 `stop()`、`awaitTermination()`、`snapshot()`。
- [x] 2.6 在 `EchoServerUtils` 中实现 `start`、`run` 与 `main`（一直运行模式注册 shutdown hook，参数错误打印用法并以非 0 退出码结束）。
- [x] 2.7 `build.gradle` 新增 `echoLoadTest`（`JavaExec`）任务。

## 3. 测试

- [x] 3.1 `EchoServerUtilsTest`：默认配置、参数覆盖、`--duration=0`、各类非法参数抛 `IllegalArgumentException`。
- [x] 3.2 测试内启动本地 Netty echo 服务端，以 20 个连接、短时长运行：已建立数为 20，请求完成数大于 0，内容错误与超时均为 0，结束后被动断开数为 0。
- [x] 3.3 测试 `duration=0` 模式：`start` 后运行中 `snapshot()` 可用，`stop()` 后会话结束，重复 `stop()` 不抛异常。
- [x] 3.4 测试目标端口无监听：建连失败数等于目标数，工具正常结束。
- [x] 3.5 测试写回错误字节的服务端：内容错误数大于 0；测试不回显的服务端：回显超时数大于 0。

## 4. 文档与验证

- [x] 4.1 新增 `docs/EchoServerUtils使用说明.md`：参数表、运行示例（定时/一直运行、多本地 IP）、统计字段说明、Linux 压测端调优（`ulimit -n`、`ip_local_port_range`、`tcp_tw_reuse`）、Non-Goals；在 `README.md` 增加入口链接。
- [x] 4.2 运行 `gradlew.bat test --tests "org.example.simple.util.echo.*"` 与 `gradlew.bat build`，确认无新增编译或弃用警告。
- [x] 4.3 运行 `openspec validate add-echo-server-load-test --strict`，核对代码、测试、文档与 OpenSpec 一致，并在本文件补充验证记录。

## 5. 需求变更：随机字符串报文

- [x] 5.1 配置以 `minMessageSize`/`maxMessageSize`（默认 16/128，`--min-message-size`/`--max-message-size`）替换 `messageSize`，校验最小值大于 0 且不大于最大值；同步更新用法文本。
- [x] 5.2 `EchoClientHandler` 每次发送前生成长度在范围内均匀随机、字符取自 `0-9A-Za-z` 的随机字符串，并以其作为本次期望回显；移除会话中的共享固定报文。
- [x] 5.3 测试：默认值与参数覆盖、最小值大于最大值抛 `IllegalArgumentException`、随机报文长度与字符集符合约定、本地回显压测在随机报文下内容错误为 0。
- [x] 5.4 更新 `docs/EchoServerUtils使用说明.md` 的参数表与工作方式说明，重新运行测试与 `openspec validate`。

## 验证记录

- `gradlew.bat test --tests "org.example.simple.util.echo.*"`：`EchoServerUtilsTest` 21 个测试全部通过（含 13 个非法参数参数化用例）。
- `gradlew.bat javadoc`：`org.example.simple.util.echo` 包无 Javadoc 警告；`compileJava`/`compileTestJava` 无新增编译或弃用警告。
- `gradlew.bat echoLoadTest --args="--host=127.0.0.1 --port=1 --clients=3 --duration=2 --connect-timeout-ms=500"`：任务可正常运行，建连失败计数为 3，按时长结束并输出汇总。
- `gradlew.bat build`（全量）：本变更测试全部通过；其余 10 个失败与本变更无关，未作改动，与上一次归档记录一致：`GlobalHttpClientUtilsTest`/`HttpClientsTest` 共 7 个（本地无可用 HTTP 服务或既有偶发断言失败）、`ExcelUtilsTest` 2 个（本地缺少测试文件）、`RpcLoopbackIntegrationTest.gracefulShutdownCompletesInFlightCall` 1 个（偶发）。
- 实施中的实现细节（与设计一致，无需调整设计）：回显收齐后同一批数据中仍有剩余字节时按内容错误处理；汇总中的"结束时在线连接"取停止前一刻的快照。
- 需求变更（随机字符串报文）后重新验证：`gradlew.bat test --tests "org.example.simple.util.echo.*"` 26 个测试全部通过（新增随机报文长度/字符集、最小值大于最大值、固定长度用例；本地回显压测在随机报文下内容错误为 0）；`gradlew.bat javadoc` 该包无警告；`openspec validate --strict` 通过。
- 2026-11-02 真实服务端验证（压测端 Windows/NIO，源地址 10.10.10.1，目标 `10.10.10.206:19090`）：
  - `--clients=10 --duration=10 --interval-ms=200`：10 个连接全部在线，完成 480 次请求，超时、内容错误、被动断开均为 0，平均延迟约 1～2ms。
  - `--clients=2000 --duration=20`：2000 个建连全部成功，峰值在线 1992（爬坡期间 8 个连接被服务端重置）；第 5～10 秒有 983 个连接的首个请求始终无回显，触发回显超时后被关闭，此后稳定在线 1009、QPS 约 1000，内容错误为 0。工具按设计正确识别出"内核已完成握手、但服务端程序未处理"的连接。
- 2026-11-02 复测：`--clients=2000 --duration=20` 结果与首轮一致（峰值在线 1994、回显超时 985、稳定在线 1009）；`--clients=1050 --duration=12 --response-timeout-ms=3000`：被动断开 1、回显超时 40，同样稳定在线 1009，确认服务端可服务连接数上限固定为 1009。
- 2026-11-02 服务端将 `Max open files` 软限制由 1024 调至 1048576 并重启后复测：
  - `--clients=2000 --duration=20`：2000 个连接全部在线，超时、内容错误、被动断开均为 0，QPS 约 2000，平均延迟约 2ms。
  - `--clients=10000 --duration=30`：10000 个连接全部在线，完成 233470 次请求，超时、内容错误、被动断开均为 0，稳定后 QPS 约 1 万、平均延迟约 4～12ms；第 8～11 秒建连中数量触顶 1000、延迟峰值约 2.4s，全部连接建立后恢复。
  - 压测端为 Windows，TCP 动态端口仅 13977 个（1024 起），单机单 IP 无法验证更高连接数。
