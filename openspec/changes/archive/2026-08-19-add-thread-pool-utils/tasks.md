## 1. 新增 ThreadPoolUtils

- [x] 1.1 在 `com.example.template.util` 包下新增 `ThreadPoolUtils` 类，私有构造函数
      （工具类不允许实例化），静态字段持有唯一的 `ThreadPoolExecutor` 单例
- [x] 1.2 构建 `ThreadPoolExecutor`：`corePoolSize`/`maximumPoolSize` 均为 4，
      `LinkedBlockingQueue` 容量 2048
- [x] 1.2a 评估过把 `keepAliveTime` 设为 60 秒并开启 `allowCoreThreadTimeOut(true)` 做空闲线程
      回收，最终决定暂不启用（见 design.md 对应决策与权衡）：`KEEP_ALIVE_SECONDS` 常量保留为
      `0L`，`allowCoreThreadTimeOut` 相关调用保留为注释、未接入，4 个线程常驻不做空闲回收
- [x] 1.3 实现命名线程的 `ThreadFactory`（内部类或匿名类）：线程名 `async-pool-N`，
      `N` 用 `AtomicInteger` 自增，非守护线程
- [x] 1.4 实现自定义 `RejectedExecutionHandler`：WARN 日志打印线程池状态
      （活跃线程数、队列大小、已完成任务数）和被拒绝任务信息，再委托
      `new ThreadPoolExecutor.AbortPolicy()` 抛出 `RejectedExecutionException`
- [x] 1.5 实现 `void execute(Runnable task)`，内部用
      `com.example.template.log4j2.MdcExecutorWrapper.wrap(task)` 包装后提交
- [x] 1.6 实现 `<T> Future<T> submit(Callable<T> task)`，同样需要携带提交时的 MDC
      上下文（`MdcExecutorWrapper` 目前只支持包装 `Runnable`，需要为 `Callable` 补一个
      等价的包装逻辑，或在 `ThreadPoolUtils` 内部直接实现，不强行复用不匹配的签名）——
      实现为 `ThreadPoolUtils` 内部私有方法 `wrapCallable`，未改动 `MdcExecutorWrapper`
- [x] 1.7 用 `Runtime.getRuntime().addShutdownHook(...)` 注册优雅关闭钩子：
      `shutdown()` + 有超时的 `awaitTermination`，超时后 `shutdownNow()`
- [x] 1.8 为类和每个方法按仓库注释规范添加中文注释

## 2. 收尾

- [x] 2.1 补充 1.2a 后重新执行 `./gradlew compileJava` 确认编译通过
- [x] 2.2 走查 `ThreadPoolUtils` 是否覆盖 proposal/design/spec 中列出的全部场景
      （提交任务、线程命名、拒绝日志+异常、traceId 传递与清理、优雅关闭）
