# netty-rpc-server Specification

## Purpose

为基于 Netty 的自研 RPC 框架提供服务端能力：以显式配置的 IO 线程组承载网络读写,将业务方法调用与 IO 线程分离到独立业务线程池执行,维护服务注册表按请求分发调用,通过空闲检测清理失效连接,并在停机时保证已受理请求的优雅退出。

## Requirements

### Requirement: 显式指定 IO 线程组大小
系统 SHALL 在构造 Netty 服务端的 `bossGroup` 与 `workerGroup` 时显式传入线程数量,不使用无参默认构造函数,以避免默认按 `CPU 核心数 * 2` 创建过多 IO 线程。

#### Scenario: 启动服务端时按配置的线程数创建 EventLoopGroup
- **WHEN** `RpcServer` 启动 Netty 服务
- **THEN** 系统 SHALL 使用显式指定线程数的 `NioEventLoopGroup(n)` 构造 `bossGroup` 和 `workerGroup`,创建出的 IO 线程数量与配置值一致,不随部署机器的 CPU 核心数变化而变化

### Requirement: IO 线程与业务线程池分离
系统 SHALL 将解码后的业务方法调用提交到独立的业务线程池执行,不在 Netty 的 EventLoop 线程中直接执行业务逻辑。

#### Scenario: 业务方法调用不阻塞 EventLoop
- **WHEN** 服务端收到一个解码完成的 RPC 请求
- **THEN** 系统 SHALL 将实际的业务方法调用提交给独立的业务线程池执行,IO 线程(EventLoop)只负责解码和提交任务,不等待业务方法执行完成

#### Scenario: 业务线程池队列积压时触发限流保护
- **WHEN** 业务线程池的任务队列已满且核心/最大线程都在忙碌
- **THEN** 系统 SHALL 按配置的拒绝策略(`CallerRunsPolicy`)让提交任务的线程自行执行该任务,而不是无限制地堆积任务导致内存耗尽

### Requirement: 服务方法注册与分发
系统 SHALL 提供服务注册表,允许使用方在服务端启动前显式注册"接口 Class → 服务实现实例"的映射;收到请求后按请求中的接口名和方法信息查找注册表并分发调用。

#### Scenario: 成功分发已注册的服务调用
- **WHEN** 服务端收到的请求中的 `interfaceName` 已通过 `registerService` 注册了对应的实现实例
- **THEN** 系统 SHALL 通过反射找到该实例上匹配 `methodName` 与参数类型的方法并调用,将执行结果封装为成功的 `RpcResponse` 写回客户端

#### Scenario: 请求的服务未注册
- **WHEN** 服务端收到的请求中的 `interfaceName` 没有在服务注册表中找到对应的实现实例
- **THEN** 系统 SHALL 返回一个标记为失败的 `RpcResponse`,错误信息中明确指出未找到该接口对应的服务,而不是抛出未处理异常导致连接异常关闭

### Requirement: 连接空闲检测
系统 SHALL 使用 `IdleStateHandler` 检测连接的读空闲状态,超过配置的空闲时间未收到任何数据(含心跳)的连接将被关闭。

#### Scenario: 长时间未收到客户端数据的连接被关闭
- **WHEN** 某个已建立的连接在超过配置的空闲超时时间内没有收到任何字节(包括心跳包)
- **THEN** 系统 SHALL 主动关闭该连接并释放相关资源

### Requirement: 优雅停机
系统 SHALL 在收到停机信号后按顺序执行:标记停机状态(不再接受触发新的业务处理)、关闭业务线程池并等待正在执行的任务在超时时间内完成、再关闭 `bossGroup`/`workerGroup`,避免正在处理中的请求被粗暴中断。

#### Scenario: 正常优雅停机流程
- **WHEN** 服务端调用 `shutdownGracefully()`
- **THEN** 系统 SHALL 先将服务标记为停机中,然后对业务线程池调用 `shutdown()` 并等待其在配置的超时时间内完成所有已提交任务,最后再关闭 `bossGroup` 与 `workerGroup`

#### Scenario: 业务线程池在超时时间内未完成所有任务
- **WHEN** 业务线程池在等待终止的超时时间到达后仍有未完成的任务
- **THEN** 系统 SHALL 调用 `shutdownNow()` 强制中断剩余任务,不无限期等待,随后继续完成 `bossGroup`/`workerGroup` 的关闭
