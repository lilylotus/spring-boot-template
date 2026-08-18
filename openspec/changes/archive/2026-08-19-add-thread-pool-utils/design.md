## Context

代码库目前有两处"为线程池准备"但一直没被实际使用的基础设施：`log4j2.MdcTaskDecorator`
（实现 Spring `TaskDecorator`，给 Spring 管理的线程池用）和 `log4j2.MdcExecutorWrapper`
（静态方法包一层 `Runnable`，给手写的 `ExecutorService` 用，文件末尾还留了一段注释示例）。
`util` 包下已有 `HttpClientUtils`：静态方法为主、内部持有一个进程级单例资源、不依赖 Spring
容器即可工作。本次新增的线程池工具类沿用这个已验证过的风格。

## Goals / Non-Goals

**Goals:**
- 提供一个进程级单例、固定规格的业务线程池：核心/最大线程数 4，有界队列容量 2048，
  线程常驻不做空闲回收。
- 线程带可识别的名称（前缀 + 序号），任务被拒绝时先打日志说明原因再抛异常，不静默丢任务。
- 提交到该线程池的任务能自动带上提交时刻的 `traceId`，异步任务里的日志能和发起请求关联上。
- 应用/JVM 关闭时优雅关闭线程池，不粗暴丢弃队列里还没跑的任务或打断正在执行的任务。

**Non-Goals:**
- 不做成可配置的连接池（不读取 `application.yml`，线程数/队列容量是硬编码的固定值）——
  当前没有"不同环境需要不同线程池规格"的用例，按需再加 `@ConfigurationProperties`。
- 不提供多个不同用途的线程池（比如 IO 密集型一个、CPU 密集型一个）——先提供一个通用的，
  等有明确的多线程池隔离需求时再扩展。
- 不封装 `ScheduledExecutorService`（定时/周期任务），只做一次性异步任务的提交。

## Decisions

- **静态工具类而非 Spring `@Component`**：线程池的构建不依赖任何 Spring 管理的协作对象
  （不像 `RedisUtils` 依赖 `RedisTemplate` 那样），跟 `HttpClientUtils` 情况一致，所以采用
  同样的"私有构造函数 + 静态字段持有单例资源 + 静态方法暴露能力"风格，不强制要求调用方
  必须通过 Spring 容器注入才能使用。
- **线程数固定为 4，不分核心/最大**：`corePoolSize` 和 `maximumPoolSize` 都设为 4——线程数
  一致时，队列打满也不会触发"临时线程"的弹性扩容语义，行为更可预测，也符合需求里
  "线程池大小为 4" 这种单一数值的表述。
- **`keepAliveTime` 保留为 0，不开启 `allowCoreThreadTimeOut`**：JDK `ThreadPoolExecutor`
  默认只对"超过 corePoolSize 的额外线程"应用 `keepAliveTime`——由于本类
  `corePoolSize == maximumPoolSize`，从来不会有额外线程，`keepAliveTime` 本身对核心线程不生效，
  必须显式调用 `allowCoreThreadTimeOut(true)` 才会让核心线程也参与空闲回收。评估下来暂不开启：
  4 个线程常驻的资源开销很小，而开启后如果任务提交节奏正好卡在空闲阈值附近，会导致线程反复
  回收/重建，行为不如"固定 4 个线程常驻"可预测。`KEEP_ALIVE_SECONDS` 常量保留为 `0L`，
  `allowCoreThreadTimeOut` 相关调用以注释形式保留在代码里，后续如果确有需要按空闲情况回收
  线程，把常量改成期望的秒数、再取消注释即可启用。
- **有界队列 `LinkedBlockingQueue`，容量 2048**：用有界队列而不是无界队列，防止任务提交速度
  持续超过消费速度时无限堆积把内存打爆；用 `LinkedBlockingQueue` 而不是 `ArrayBlockingQueue`
  是因为前者是 JDK `ThreadPoolExecutor` 场景下最常用、性能更均衡的有界队列实现。
- **自定义 `ThreadFactory`**：线程名格式 `async-pool-N`（`N` 从 1 开始自增，用
  `AtomicInteger` 保证并发安全），并显式设置为非守护线程（`setDaemon(false)`），
  确保 JVM 不会在任务还没执行完时因为"只剩守护线程"而提前退出。
- **拒绝策略：先记录日志、再委托 `AbortPolicy`**：自定义 `RejectedExecutionHandler`，
  触发时用 WARN 级别记录线程池当前状态（核心线程数、活跃线程数、队列积压任务数、
  已完成任务总数）和被拒绝任务的信息，方便排查"为什么积压/为什么被拒绝"；日志打完之后
  委托给 `new ThreadPoolExecutor.AbortPolicy()`，仍然向调用方抛出
  `RejectedExecutionException`——只做"可观测"（打日志），不改变"任务确实被拒绝了"这个
  事实，调用方必须感知失败并自行决定重试/降级，而不是让工具类替业务做静默丢弃的决定。
- **复用 `MdcExecutorWrapper` 传递 traceId**：`execute`/`submit` 内部用
  `MdcExecutorWrapper.wrap(...)` 包一层任务再提交给底层 `ThreadPoolExecutor`，这样异步任务
  执行时 `ThreadContext`(MDC) 里能拿到提交线程当时的 `traceId`，异步任务打的日志可以和
  发起请求的日志通过 `traceId` 关联起来——这正是 `TraceIdFilter` 注释里提到的"线程池复用
  线程时 traceId 串号"问题的解决方式，`MdcExecutorWrapper` 本身已经在 `finally` 里做了
  `ThreadContext.clearAll()` 清理，不会有残留污染下一个任务的问题。
- **JVM 关闭钩子做优雅关闭**：因为不是 Spring 管理的 Bean，没有 `@PreDestroy`/
  `DisposableBean` 这类生命周期钩子可用，所以在静态初始化阶段用
  `Runtime.getRuntime().addShutdownHook(...)` 注册一个钩子，调用
  `executor.shutdown()` + `awaitTermination`（设置一个合理超时，超时后 `shutdownNow()`
  强制中断），让已提交的任务尽量跑完，而不是 JVM 退出时线程被直接杀死。

## Risks / Trade-offs

- [风险] 固定 4 个线程、队列 2048，在任务提交速率长期超过 4 个线程的处理能力时，队列会持续
  积压直到打满、最终触发拒绝策略 → 缓解：拒绝时有 WARN 日志暴露积压情况，运维/开发能及时
  发现并排查是任务本身太慢还是提交速率异常，而不是被动等用户反馈"功能不工作了"。
- [权衡] 线程数/队列容量是硬编码值，不能按环境差异化配置 → 可接受，遵循 Non-Goals 里
  "按需再加配置化" 的原则，当前没有多环境差异化的实际需求。
- [风险] `AbortPolicy` 会向调用方抛出未受检异常 `RejectedExecutionException`，如果调用方
  没有妥善处理（比如 catch 之后什么都不做），任务会真的丢失 → 缓解：这是调用方的责任边界，
  工具类通过日志 + 抛异常已经做到"不静默"，具体的重试/降级策略应该由业务代码决定。
- [权衡] 线程常驻不做空闲回收，即使长期没有异步任务，这 4 个线程也会一直占用（少量）内存
  → 可接受，4 个线程的常驻开销可忽略不计，换来的是更简单可预测的行为；如果后续场景变化，
  代码里已经留好 `KEEP_ALIVE_SECONDS` 常量和注释掉的 `allowCoreThreadTimeOut` 调用，改一下
  常量值、取消注释即可启用空闲回收。
