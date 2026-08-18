## ADDED Requirements

### Requirement: 统一封装固定规格业务线程池
系统 SHALL 提供一个 `ThreadPoolUtils` 工具类，内部维护一个进程级单例线程池：核心/最大线程数均为
4，使用容量为 2048 的有界队列，线程常驻不做空闲回收。业务代码 SHALL 通过
`ThreadPoolUtils.execute`/`submit` 提交异步任务，而不是各自创建 `ExecutorService`。

#### Scenario: 提交 Runnable 任务
- **WHEN** 调用方执行 `ThreadPoolUtils.execute(runnable)`，且线程池未打满
- **THEN** 该任务被提交到线程池异步执行

#### Scenario: 提交 Callable 任务
- **WHEN** 调用方执行 `ThreadPoolUtils.submit(callable)`，且线程池未打满
- **THEN** 该任务被提交到线程池异步执行，返回对应的 `Future`

### Requirement: 线程带可识别名称
线程池创建的工作线程 SHALL 使用统一前缀加自增序号的名称（如 `async-pool-1`），不使用 JDK 默认的
`pool-N-thread-M` 命名。

#### Scenario: 查看线程名
- **WHEN** 线程池创建一个新的工作线程
- **THEN** 该线程的名称带有统一前缀，且序号在同一个线程池实例内不重复

### Requirement: 队列打满时记录拒绝原因并抛出异常
当线程池的核心线程与队列都已被占满、无法接纳新任务时，系统 SHALL 先以 WARN 级别记录一条包含
线程池当前状态（活跃线程数、队列积压任务数、已完成任务数）和被拒绝任务信息的日志，再向调用方
抛出 `RejectedExecutionException`，不允许在未记录日志的情况下静默丢弃任务。

#### Scenario: 队列打满触发拒绝
- **WHEN** 调用方执行 `ThreadPoolUtils.execute(runnable)`，且核心线程和队列(2048)都已占满
- **THEN** 系统先记录一条包含拒绝原因的 WARN 日志，然后向调用方抛出
  `RejectedExecutionException`

### Requirement: 异步任务携带提交时的 traceId
提交到该线程池的任务 SHALL 能在执行时访问到提交线程当时的 `traceId`（通过 MDC/`ThreadContext`
传递），使异步任务打印的日志可以和发起请求的日志通过 `traceId` 关联；任务执行结束后 SHALL 清理
该线程的 MDC 上下文，不污染线程池中后续复用该线程执行的下一个任务。

#### Scenario: 异步任务内读取 traceId
- **WHEN** 请求处理线程当时的 `ThreadContext` 中存在 `traceId`，调用方在该请求处理过程中执行
  `ThreadPoolUtils.execute(runnable)`
- **THEN** `runnable` 在线程池的工作线程上执行时，能够从 `ThreadContext` 读取到与提交线程相同
  的 `traceId`

#### Scenario: 任务执行完毕后清理 MDC
- **WHEN** 提交给线程池的任务执行完毕（无论成功还是抛出异常）
- **THEN** 执行该任务的工作线程的 `ThreadContext` 被清空，不会把这次的 `traceId` 带到该线程
  后续执行的下一个任务里

### Requirement: 应用关闭时优雅关闭线程池
系统 SHALL 在 JVM 关闭时尝试优雅关闭该线程池：停止接收新任务、等待已提交任务在合理超时时间内
执行完毕，超时仍未完成的再强制中断，而不是让 JVM 退出时线程被直接杀死、任务状态不可控。

#### Scenario: JVM 正常退出
- **WHEN** JVM 收到关闭信号（如进程正常退出）
- **THEN** 线程池停止接收新任务，已提交但未完成的任务在超时时间内继续执行完毕后线程池才终止
