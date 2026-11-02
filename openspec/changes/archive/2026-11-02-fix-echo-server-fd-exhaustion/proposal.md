## Why

使用 `EchoServerUtils` 压测 C++ epoll echo 服务端时，在进程打开文件数软限制为 1024 的情况下，服务端稳定只服务 1009 个连接，其余连接完成 TCP 握手后一直无回显（回显超时），而不是按代码意图被快速拒绝。代码审查与 Linux 本机复现确认原因与若干相关问题：

- `shedConnection()` 依赖每个 worker 的预留 fd，但 fd 表是进程级共享的：worker A 关闭预留 fd 后，空出的 fd 号可能被 worker B 的 `accept4` 抢走，A 随后 `accept4` 与 `open("/dev/null")` 都以 `EMFILE` 失败，`spare_fd_` 变为 `-1` 且永不恢复；此后 A 的监听队列再也不会被 `accept`，水平触发的监听 fd 使 `epoll_wait` 立即返回，A 线程空转占满 CPU。
- 即使修复空转，只要进程真正耗尽 fd，预留 fd 的竞争就无法完全避免，仍有大量连接堆积在监听队列中等不到回显。
- 未忽略 `SIGPIPE`，stdout 为已关闭的管道时写日志会导致进程被杀死。
- 源码与 `Makefile` 混放在 Java 生产代码包目录中，且只有单个版本，缺少问题记录与使用说明。

## What Changes

- 服务端源码统一放在 `src/main/java/org/example/simple/util/echo/echo-server-cpp/`：
  - `echo_server-ubuntu-v1.cpp`：最终版本，`Makefile` 编译该文件生成 `echo_server`；
  - `echo_server-windows-v1.cpp`：中间版本（暂停/恢复接入、`SO_KEEPALIVE`、中文日志），保留作参考；
  - `echo_server-v0.cpp`：原始版本，保留作对照；
  - `echo-server-optimize.md`：问题分析、修复与验证数据；`README.md`：文件说明、编译运行与压测建议；`.gitignore` 忽略编译产物。
- 新增进程级连接数上限 `ConnectionLimit`：`最大连接数 = fd 软上限 - 16 - 5 × worker 数`，超过上限的连接 accept 后立即关闭，使进程不走到 fd 耗尽；上限不足 1 时启动失败。
- fd 耗尽兜底（`ENFILE`、继承大量 fd）：预留 fd 丢失时不再关闭无效 fd 并在每次调用时重新打开；拒绝失败且错误为 `EMFILE`/`ENFILE` 时暂停监听，本 worker 关闭连接时或 100ms 后恢复，每轮事件循环检查重试时间。
- `main` 开头忽略 `SIGPIPE`。
- 不改变回显协议、线程模型、命令行参数（`-p`、`-w`、`-q`、`-h`）与默认端口 `19090`。

## Capabilities

### New Capabilities

- `echo-epoll-server`：定义 C++ epoll echo 服务端的回显行为、连接数上限、fd 耗尽时的拒绝/暂停监听行为与信号约定。

### Modified Capabilities

无。

## Impact

- 目录：`src/main/java/org/example/simple/util/echo/echo-server-cpp/`（新增上述源码、`Makefile`、`.gitignore`、`README.md`、`echo-server-optimize.md`）。
- `docs/EchoServerUtils使用说明.md` 增加指向服务端说明的链接。
- 不影响 Java 代码、Gradle 构建与现有测试（Gradle 只编译 `.java`，该目录中的 C++ 文件与文档不参与构建）。
