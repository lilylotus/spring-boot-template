## Why

项目缺少统一执行异步多线程任务的便捷入口，调用方容易重复创建线程池或使用不一致的并发配置。需要在通用工具包中封装一个内部四线程执行器，让调用方直接执行或提交任务而无需接触线程池实例。

## What Changes

- 在 `org.example.simple.util` 包新增 `ExecutorServiceUtils` 静态工具类。
- 工具类内部维护固定 4 个工作线程的共享执行器，不向调用方暴露 `ExecutorService`。
- 提供直接执行 `Runnable` 的 `execute` 方法。
- 提供提交 `Runnable` 和 `Callable<T>` 并返回 `Future` 的 `submit` 方法。
- 使用容量为 1024 的有界等待队列；4 个线程均忙且队列已满时拒绝新任务。
- 拒绝任务时抛出中文 `RejectedExecutionException`，说明活动线程数、当前排队数和队列容量。
- 使用具名守护线程，避免内部共享线程阻止 JVM 正常退出。
- 拒绝空任务，并补充并发执行、任务结果、异常传播、队列溢出和参数校验单元测试。

## Capabilities

### New Capabilities

- `thread-pool-utility`: 定义内部四线程执行器的任务执行、任务提交、结果返回、有界队列和拒绝错误行为。

### Modified Capabilities

无。

## Impact

- 新增 `src/main/java/org/example/simple/util/ExecutorServiceUtils.java`。
- 新增 `src/test/java/org/example/simple/util/ExecutorServiceUtilsTest.java`。
- 不引入第三方依赖，不修改现有密码学、HTTP 或 RPC API。
