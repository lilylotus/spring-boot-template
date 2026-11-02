## Context

被测服务端是多线程 epoll echo 服务端：每个 worker 一个 epoll 实例和一个 `SO_REUSEPORT` 监听 socket，水平触发，每事件读一次，未发完的数据进入 `pending` 并注册 `EPOLLOUT`，有 1MB 高水位背压。整体结构正确，本次修复 fd 耗尽处理缺陷并做健壮性小改，不重构。

真实压测数据佐证了缺陷：软限制 1024 时稳定服务 1009 个连接，1009 = 1024 − 3（标准输入/输出/错误）− 4 × 3（每 worker 占用监听、epoll、eventfd、预留 fd），对应 3 个 worker；多余连接表现为挂起超时而非被拒绝。

实施分两步：先按审查建议在 `echo_server-windows-v1.cpp` 中实现暂停/恢复接入；用户随后在 Linux 本机复现时发现仅靠暂停/恢复仍有约 750～790 个连接挂起，于是在 `echo_server-ubuntu-v1.cpp` 中增加连接数上限，并以该版本为最终实现。分析与数据见 `src/main/java/org/example/simple/util/echo/echo-server-cpp/echo-server-optimize.md`。

## Goals / Non-Goals

**Goals:**
- 进程在常规情况下不走到 fd 耗尽，多余连接被快速拒绝（客户端立即感知断开，而非长时间挂起）。
- fd 真正耗尽时不空转、不永久停止 `accept`；fd 恢复后自动继续接入。
- 进程不因 `SIGPIPE` 退出。
- 保留各版本源码与问题记录，`make` 编译最终版本。

**Non-Goals:**
- 不实现应用层空闲超时，最终版本不开启 `SO_KEEPALIVE`（列入后续建议）。
- 不支持手动指定连接数上限，只按 fd 软上限自动计算。
- 不改变日志架构（仍为每轮事件循环批量写 stdout）；压测建议通过 `-q` 关闭逐请求日志。
- 不修改 `listen` backlog 取值（仍为 `SOMAXCONN`）。
- 不将 C++ 编译纳入 Gradle。

## Decisions

### 第一层：进程级连接数上限

`ConnectionLimit` 持有一个 `std::atomic<long>` 计数，所有 worker 共享：

- `最大连接数 = RLIMIT_NOFILE 软上限 - kReservedDescriptors(16) - kDescriptorsPerWorker(5) × worker 数`。每个 worker 预留监听、epoll、eventfd、预留 fd 各 1 个，再加 1 个用于暂存刚 accept、即将被拒绝的连接；固定 16 个留给标准输入输出与继承的 fd。
- 使用软上限，因为 `accept` 触发的就是软上限；`ulimit -n` 调高后重启即可提高上限。
- `accept4` 成功后 `tryAcquire()`：`fetch_add` 结果小于上限则放行，否则立即回退计数并 `close` 连接，记录 `connection limit reached` 日志。回退期间计数可能短暂超过上限，每 worker 最多 1，正好由每 worker 第 5 个预留 fd 覆盖。计数器不保护其他数据，使用 relaxed 内存序。
- 连接关闭（`closeConnection`）及 `epoll_ctl` 注册失败时 `release()` 归还名额。
- 算出的上限不足 1 时启动失败，退出码 1；启动时打印实际上限。

取舍：fd 表进程共享，预留 fd 方案在真正耗尽时无法可靠拒绝连接；在耗尽之前按计数拒绝，是唯一能保证"多余连接立即断开"的方式。

### 第二层：fd 耗尽兜底

连接数上限防不住的情况（系统级 `ENFILE`、进程继承大量 fd）：

1. `shedConnection()`：预留 fd 有效时才关闭；`accept4` 成功则立即关闭该连接；无论成功与否都重新打开预留 fd（每次调用都会重试补开）。
2. 拒绝失败且 `accept4` 的错误为 `EMFILE`/`ENFILE` 时 `pauseAccept()`：`EPOLL_CTL_MOD` 将监听 fd 事件置 `0`（保留注册），设置 `accept_paused_` 与重试时间 `now + 100ms`。其他失败（如队列为空）不会空转，不暂停。
3. 恢复：本 worker `closeConnection()` 时立即 `resumeAccept()`；`run()` 中暂停期间 `epoll_wait` 使用 100ms 超时，且每轮事件循环结束时检查是否已到重试时间。不只依赖超时返回 0，是因为繁忙 worker 的 `epoll_wait` 可能一直不会超时。fd 仍然耗尽时，下一次 accept 会再次暂停，不会空转。

取舍：不引入跨 worker 全局锁保护预留 fd，因为竞争源是任意线程的 `accept4`/`open`，加锁无法覆盖全部 fd 分配点，且会让热路径串行化。

### 信号

`main` 开头 `signal(SIGPIPE, SIG_IGN)`，在创建线程前设置；socket 写入仍保留 `MSG_NOSIGNAL`。日志写入失败只丢弃该行。

### 版本与目录

用户将源码放在 `src/main/java/org/example/simple/util/echo/echo-server-cpp/`，与压测工具同目录便于对照；Gradle 只编译 `.java`，不受影响。保留三个版本：`v0`（原始，对照）、`windows-v1`（中间版本：暂停/恢复接入、`SO_KEEPALIVE`、中文注释与日志）、`ubuntu-v1`（最终版本）。`Makefile` 编译 `echo_server-ubuntu-v1.cpp` 生成 `echo_server`，`.gitignore` 忽略该产物。

### 语言约定的例外

最终版本 `echo_server-ubuntu-v1.cpp` 的注释为中英对照，用法说明与日志为英文，未按仓库"自然语言使用中文"约定改为中文；该决定由用户在归档时确认以该版本为准，README 中如实说明。中文化日志的实现保留在 `windows-v1` 中。

## Risks / Trade-offs

- **上限偏保守**：固定预留 16 个 fd，日志重定向、动态库等占用 fd 极少时略有浪费，换取不走到 fd 耗尽。
- **`prlimit` 不生效**：连接数上限在启动时计算，运行中调整 fd 上限需重启服务端才会生效。
- **无 keepalive**：异常消失的客户端会长期占用 fd 与连接名额，长期运行需后续补充 `SO_KEEPALIVE` 或空闲超时。
- **`ENFILE` 未实测**：兜底路径依赖代码审查，未在真实系统级 fd 耗尽场景下验证。
- **建连高峰延迟未定位**：Windows 压测机压 10000 连接时建连阶段延迟峰值约 2.4s，本机回环未复现，需在实际链路用 `nstat` 观察队列溢出与重传后再判断。
