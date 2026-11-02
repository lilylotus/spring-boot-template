# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/echo-epoll-server/spec.md` 的明确确认：fd 耗尽时"拒绝优先、失败则暂停监听、100ms 恢复"、不加跨 worker 全局锁、忽略 `SIGPIPE`。
- [x] 1.2 归档前由用户确认以 `echo_server-ubuntu-v1.cpp` 为最终版本（新增连接数上限，日志与用法为英文，不开启 `SO_KEEPALIVE`），`Makefile` 编译该版本。

## 2. 实现

- [x] 2.1 在 `echo_server-windows-v1.cpp` 中实现暂停/恢复接入、预留 fd 补开、忽略 `SIGPIPE`、`SO_KEEPALIVE` 与中文注释/日志（中间版本）。
- [x] 2.2 在 `echo_server-ubuntu-v1.cpp` 中实现 `ConnectionLimit` 进程级连接数上限、启动时计算并打印上限、上限不足 1 时启动失败（用户在 Linux 上完成）。
- [x] 2.3 在 `echo_server-ubuntu-v1.cpp` 中保留暂停/恢复接入兜底：仅在 `EMFILE`/`ENFILE` 时暂停，每轮事件循环检查重试时间，预留 fd 每次调用重试补开（用户在 Linux 上完成）。
- [x] 2.4 源码统一放在 `src/main/java/org/example/simple/util/echo/echo-server-cpp/`，保留 `echo_server-v0.cpp` 作对照；`Makefile` 改为编译 `echo_server-ubuntu-v1.cpp`；`.gitignore` 忽略 `echo_server`。

## 3. 文档

- [x] 3.1 `echo-server-cpp/README.md`：版本文件说明、最终版本设计要点、编译、参数、运行示例、压测建议（`-q`、`ulimit -n`、`net.core.somaxconn`、压测统计口径）。
- [x] 3.2 `echo-server-cpp/echo-server-optimize.md`：问题分析、修复方式、验证数据与待处理事项（用户编写）。
- [x] 3.3 在 `docs/EchoServerUtils使用说明.md` 增加指向服务端说明的链接。

## 4. 验证

- [x] 4.1 静态核对：代码、README、Makefile 与 design 一致；`openspec validate fix-echo-server-fd-exhaustion --strict` 通过。
- [x] 4.2 Linux 本机回环 fd 耗尽测试（fd 上限 1024、4 个 worker、2000 个客户端）：多余连接被立即断开、无挂起，worker CPU 0～2%（用户执行，数据见 `echo-server-optimize.md`）。
- [x] 4.3 Linux 本机回环高上限回归（20 万 fd 上限、10000 个连接、每秒建连 900 个）：全部在线、无错误（用户执行）。
- [x] 4.4 在本文件补充验证记录。

## 验证记录

- `echo_server-windows-v1.cpp` 静态核对（开发机为 Windows，无 g++/WSL，无法本地编译）：括号配平；`SIGPIPE` 忽略、`SO_KEEPALIVE`、暂停/恢复接入、按暂停时长恢复、预留 fd 补开、析构仅关闭有效预留 fd 均已落实。
- 用户在 Linux 本机回环上对 `echo_server-ubuntu-v1.cpp` 的验证（摘自 `echo-server-optimize.md`）：
  - fd 上限 1024、4 个 worker、2000 个客户端：正常回显 988、被断开 1012、已连上但等不到回显 0；每个 worker CPU 0～2%；占用 fd 1007，等待队列无堆积。对比修复前：等不到回显约 750～990，worker CPU 93～99%。
  - 连续三轮占满再全部断开：每轮恢复到 988 个在线，无名额泄漏。
  - fd 上限设为 30：启动报错，退出码 1。
  - 20 万 fd 上限、10000 个连接、每秒建连 900 个：全部在线，无错误，平均延迟约 0.6ms。
  - 关闭 stdout 管道后发请求：进程存活，回显正常；压测后发送 `SIGTERM`：正常退出，退出码 0。
- 修复前在真实链路（Windows 压测机 → `10.10.10.206:19090`）的观测：软限制 1024 时稳定在线 1009，多余连接回显超时；软限制调至 1048576 后 10000 个连接全部在线、无错误（详见 `add-echo-server-load-test` 变更验证记录）。
- 未覆盖：未实际触发系统级 fd 耗尽（`ENFILE`）；`echo_server-ubuntu-v1.cpp` 尚未经过 Windows 压测机与真实网络回归；`make` 在 `-Wall -Wextra` 下是否无警告未记录。
- 遗留事项：建连高峰期延迟（约 2.4s）未定位；`SO_KEEPALIVE`/空闲超时未纳入最终版本；最终版本日志与用法为英文（用户确认的例外）。
- `openspec validate fix-echo-server-fd-exhaustion --strict`：通过。
