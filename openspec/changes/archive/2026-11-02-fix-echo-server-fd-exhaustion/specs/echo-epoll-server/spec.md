# Spec Delta

## Purpose
定义 `src/main/java/org/example/simple/util/echo/echo-server-cpp/echo_server-ubuntu-v1.cpp` 多线程 epoll echo 服务端的回显、连接数上限、fd 耗尽处理与信号行为。

## ADDED Requirements

### Requirement: 原样回显
服务端 SHALL 将每个连接收到的字节按原顺序原样写回该连接，写不完的部分排队等待可写后继续发送。

#### Scenario: 正常回显
- **WHEN** 客户端发送任意字节序列
- **THEN** 客户端收到与发送内容逐字节相同、顺序一致的回显

### Requirement: 连接数上限
服务端 SHALL 按 `fd 软上限 - 16 - 5 × worker 数` 计算进程级连接数上限，超过上限的连接在 accept 后立即关闭，连接关闭时归还名额；上限不足 1 时启动失败。

#### Scenario: 超过上限的连接被立即断开
- **WHEN** 在线连接数已达到上限后仍有新连接到达
- **THEN** 新连接被服务端立即关闭，不会挂起等待回显；已在线连接的回显不受影响

#### Scenario: 名额归还
- **WHEN** 连接占满上限后全部断开，再次建立同样数量的连接
- **THEN** 在线连接数恢复到上限，没有名额泄漏

#### Scenario: fd 上限过低
- **WHEN** fd 软上限不足以容纳任何连接
- **THEN** 服务端启动失败并以退出码 1 退出

### Requirement: fd 耗尽时不空转且可恢复
服务端 SHALL 在 `accept` 因 `EMFILE` 或 `ENFILE` 失败时尽力拒绝待接入连接；无法拒绝时暂停对监听 socket 的关注，并在本 worker 关闭连接或 100ms 后恢复，期间不得空转占满 CPU。

#### Scenario: fd 耗尽期间
- **WHEN** 进程打开文件数达到上限后仍有新连接到达
- **THEN** worker 线程不会持续 100% CPU，fd 释放后服务端恢复接入新连接

### Requirement: 信号处理
服务端 SHALL 忽略 `SIGPIPE`，对已接入连接开启 `TCP_NODELAY`；收到 `SIGINT` 或 `SIGTERM` 时停止全部 worker 并以退出码 0 退出。

#### Scenario: stdout 管道关闭
- **WHEN** 服务端 stdout 连接的管道已关闭后服务端写日志
- **THEN** 服务端进程不退出，继续提供回显服务

#### Scenario: 优雅退出
- **WHEN** 服务端收到 `SIGTERM`
- **THEN** 服务端停止全部 worker 并以退出码 0 退出
