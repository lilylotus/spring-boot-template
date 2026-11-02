# echo_server

基于非阻塞 socket 与 epoll 的多线程 TCP echo 服务端（仅支持 Linux），收到什么字节就原样写回。配套压测工具见 [EchoServerUtils 使用说明](../../../../../../../../../docs/EchoServerUtils使用说明.md)。

## 文件

| 文件 | 说明 |
| --- | --- |
| `echo_server-ubuntu-v1.cpp` | **最终版本**，`make` 编译的就是它：连接数上限 + fd 耗尽时暂停/恢复接入 + 忽略 `SIGPIPE` |
| `echo_server-windows-v1.cpp` | 中间版本：fd 耗尽时暂停/恢复接入、`SO_KEEPALIVE`、中文日志，没有连接数上限，仅作参考 |
| `echo_server-v0.cpp` | 原始版本，存在 fd 耗尽时 worker 空转、多余连接挂起的问题，仅作对照 |
| `echo-server-optimize.md` | 问题分析、修复方式与验证数据 |

## 设计要点（最终版本）

- 每个 worker 线程拥有独立的 epoll 实例和 `SO_REUSEPORT` 监听 socket，由内核在 worker 间分配连接，连接状态无需加锁。
- 水平触发，每个事件只读一次，避免繁忙连接饿死同一 worker 上的其他连接。
- 写不完的回显进入队列并关注 `EPOLLOUT`；单连接积压超过 1MB 时暂停读取该连接（背压）。
- 过载两层防护：
  1. **连接数上限**：`最大连接数 = fd 软上限 - 16 - 5 × worker 数`，所有 worker 共用一个原子计数器；超过上限的连接被 accept 后立即关闭，客户端看到"连接成功后马上被断开"。
  2. **fd 耗尽兜底**：系统级 fd 耗尽（`ENFILE`）或继承大量 fd 时，先用预留 fd 拒绝连接，拒绝失败则暂停监听，本 worker 关闭连接时或 100ms 后恢复，不会空转。
- 已接入连接开启 `TCP_NODELAY`；进程忽略 `SIGPIPE`；`SIGINT`/`SIGTERM` 触发优雅退出。
- 未开启 `SO_KEEPALIVE`，也没有空闲超时：客户端断电或断网后，连接会一直占用 fd 和连接名额。
- 用法说明与日志为英文（注释为中英对照）。

## 编译

```bash
make            # 用 echo_server-ubuntu-v1.cpp 生成 ./echo_server，编译选项 -std=c++17 -O2 -Wall -Wextra -pthread
make clean
```

## 运行

```bash
./echo_server                 # 监听 19090，worker 数为 CPU 核数，输出连接与请求日志
./echo_server -p 9000 -w 4    # 指定端口与 worker 数
./echo_server -q              # 关闭连接与请求日志
./echo_server -h              # 打印用法
```

| 参数 | 含义 | 默认值 |
| --- | --- | --- |
| `-p` | 监听端口 | `19090` |
| `-w` | worker 线程数（1～1024） | CPU 核数 |
| `-q` | 关闭连接与请求日志 | 开启日志 |
| `-h` | 打印用法 | - |

启动时会打印实际的连接数上限，例如 `with 4 worker(s), max 988 connections`。算出的上限不足 1 时启动失败，退出码为 1。

## 压测建议

- **使用 `-q`**：默认每个请求输出一行日志，并在全局锁内写 stdout，终端输出速度会直接拖慢所有 worker。
- **调大打开文件数**：连接数上限随 fd 软上限变化，默认 1024、4 个 worker 时最多 988 个连接。普通用户可在硬限制内调高软限制：

  ```bash
  ulimit -n 1048576 && ./echo_server -q
  # systemd 服务使用 LimitNOFILE=1048576
  ```

  `prlimit` 调整已运行进程的 fd 上限不会改变已经算好的连接数上限，需要重启服务端。
- **调大监听队列**：`listen` 使用 `SOMAXCONN`，实际长度受 `net.core.somaxconn` 限制。大量连接同时涌入并出现队列溢出时再调大：

  ```bash
  sysctl -w net.core.somaxconn=65535
  sysctl -w net.ipv4.tcp_max_syn_backlog=65535
  ```

- **压测工具统计口径**：超过连接数上限的连接会被计为"被动断开"，而不是"回显超时"。
