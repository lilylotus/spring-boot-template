## Why

目前代码库里没有统一管理的业务线程池，如果哪个模块需要异步执行任务，只能各自手写
`new ThreadPoolExecutor(...)` 或裸用 `Executors`，容易出现线程数不受控、队列无界导致内存暴涨、
线程名默认是 `pool-N-thread-M` 不便于排查、任务被拒绝时又没有任何提示（静默丢失）等问题。
需要一个统一的线程池工具类，提供固定规格、可观测的线程池，业务代码直接调用即可提交异步任务。

## What Changes

- 新增 `ThreadPoolUtils` 工具类（`com.example.template.util` 包，静态方法风格，对齐
  `HttpClientUtils` 的写法），内部维护一个进程级单例 `ThreadPoolExecutor`：
  - 核心/最大线程数均为 4（固定大小线程池，不做弹性伸缩），线程常驻不做空闲回收
    （`keepAliveTime` 保留为 0，且不开启 `allowCoreThreadTimeOut`，避免线程在负载临界点
    附近反复回收/重建产生抖动；预留了 `KEEP_ALIVE_SECONDS` 常量，后续如有需要可按需开启）
  - 阻塞队列容量 2048（`LinkedBlockingQueue`，有界，避免无界队列把内存打爆）
  - 自定义 `ThreadFactory`，线程名带统一前缀 + 序号，便于日志/线程 dump 排查
  - 自定义拒绝策略：队列打满时先打印一条包含拒绝原因（线程池当前状态：核心数、活跃数、
    队列积压量、已完成任务数等）的 WARN 日志，再委托给 `AbortPolicy` 抛出
    `RejectedExecutionException`，不静默丢弃任务
  - 提交任务时复用代码库里已有但尚未被使用的 `MdcExecutorWrapper`（`log4j2` 包），
    让异步任务里打的日志也能带上主线程的 `traceId`，与现有链路追踪机制打通
  - 暴露 `execute(Runnable)`、`submit(Callable<T>)` 两个提交入口，以及 JVM 关闭钩子做
    优雅关闭（不粗暴 kill 掉正在执行的任务）
- 不改动任何现有类，纯新增。

## Capabilities

### New Capabilities
- `thread-pool-utils`：统一封装的固定规格业务线程池工具类，提供异步任务提交能力，
  内置有界队列、命名线程、带日志的拒绝策略、MDC(traceId) 传递、优雅关闭。

### Modified Capabilities
（无）

## Impact

- 新增：`com.example.template.util.ThreadPoolUtils`
- 依赖：无新增第三方依赖，复用 JDK `java.util.concurrent` 与代码库已有的
  `com.example.template.log4j2.MdcExecutorWrapper`
