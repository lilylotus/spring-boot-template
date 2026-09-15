## Context

本仓库是一个 Spring Boot 3.5 + Java 21 的"启动模板"仓库,目前服务间调用只有 OpenFeign(HTTP)一条路径。`Java_RPC_Netty设计方案.md` 给出了一套完整的自定义二进制协议 RPC 框架设计(协议层、客户端异步核心、超时机制、负载均衡、服务端线程模型、心跳与优雅停机),本次改动把该设计落地为 `com.example.template.rpc` 包下的一套可独立运行、可被其它服务复用的代码。

**关键约束(用户明确要求)**:Netty 服务端的 `bossGroup`/`workerGroup` 必须显式指定线程数,不能使用 `new NioEventLoopGroup()` 这种无参默认构造——默认构造会用 `Runtime.availableProcessors() * 2`,在高核数机器上会创建过多线程,浪费资源且加剧上下文切换开销。其余参数(帧解码器上限、业务线程池核心/最大线程数、队列容量、心跳间隔、连接超时等)按设计文档给出的数值作为默认调优参数,不做额外精细化调参。

## Goals / Non-Goals

**Goals:**
- 落地设计文档中的协议层、客户端、服务端三大部分,端到端可运行(自带一个示例接口验证)。
- 服务端 IO 线程组显式指定大小;业务线程池与 IO 线程严格分离。
- 客户端同时支持同步阻塞调用与异步 `CompletableFuture` 调用。
- 超时机制通过 `HashedWheelTimer` + `ConcurrentHashMap` 原子 `remove()` 避免超时与正常响应的竞态重复处理。
- 负载均衡与服务发现做成可插拔接口,默认提供轮询负载均衡 + 静态地址列表服务发现。
- 遵循仓库现有代码风格(Java 代码风格 skill、类/非平凡方法注释密度参考 `MybatisPlusConfig`/`TraceIdFilter`/`RedisConfig`)。

**Non-Goals:**
- 不接入真实的 Nacos 服务发现(只做可扩展的 `ServiceDiscovery` 接口 + 静态地址列表默认实现)。
- 不实现一致性哈希/加权负载均衡等设计文档中提到但未要求立即实现的策略(接口预留扩展点,默认只实现轮询)。
- 不实现分级超时、限流、失败重试、全链路监控等"生产收尾"清单项(设计文档第六节),这些留待后续独立改动按需增量添加。
- 不做跨语言序列化(Protobuf)支持,默认 Jackson JSON 序列化,序列化方式做成可插拔接口。
- 不将 RPC 框架自动接入现有 Spring Boot 应用的自动配置/自动启动(避免影响仓库现有测试所依赖的本地基础设施假设),而是提供独立可手动启动的组件。

## Decisions

1. **序列化选型:默认 Jackson JSON,接口可插拔**
   设计文档建议 Protostuff/Kryo(性能更好)或 Jackson JSON。选择 Jackson JSON 作为默认实现,原因:(a) 仓库 `RedisConfig` 已经用 Jackson 做序列化,技术栈统一,不引入新的第三方序列化库依赖;(b) Kryo/Protostuff 默认不做类型白名单校验存在反序列化安全风险,而本仓库 `RedisConfig` 特意把 Jackson 的 `activateDefaultTyping` 限制在 `com.example`/`java.util` 包以内规避这个问题,风格上应保持一致;(c) 通过 `RpcSerializer` 接口隔离,后续如需追求极致性能可以无侵入地替换为 Kryo/Protostuff 实现。
   **替代方案**:直接用 Kryo(性能更优但需要额外处理线程安全——Kryo 实例本身非线程安全,需要 `ThreadLocal` 池化,且反序列化白名单要单独维护)——因为增加了不小的复杂度和新依赖,不作为默认选型。

2. **Netty 线程组显式指定线程数**
   `bossGroup` 固定 1 个线程(单机场景下一个 acceptor 线程足够,避免过度并发 accept);`workerGroup` 显式传入一个较小的固定线程数(与业务线程池的核心线程数解耦,IO 线程只做编解码和读写转发,不跑业务逻辑,不需要很多线程)。两者都通过构造函数显式传入线程数 `new NioEventLoopGroup(n)`,不使用无参构造。
   **替代方案**:使用默认无参构造(`CPU核心数*2`)——在高核数服务器上会创建远超实际需要的 IO 线程数,增加上下文切换开销且与"IO 线程应该保持精简"的设计初衷冲突,已被用户明确否决。

3. **业务线程池参数沿用设计文档给出的默认值**
   核心线程数 16、最大线程数 32、空闲存活 60 秒、`LinkedBlockingQueue(1000)` 有界队列、`CallerRunsPolicy` 拒绝策略(队列满时退化为调用者线程执行,起到天然限流/背压效果而不是直接丢请求)。这些是设计文档给出的参数,本次改动直接采用,不做进一步调优,后续如有实际压测数据再调整。

4. **协议帧结构与解码器参数**
   帧格式:魔数(4B)+ 版本(1B)+ 消息类型(1B)+ 序列化方式(1B)+ requestId(8B)+ 消息长度(4B)+ 消息体,长度字段偏移量 15(即消息体长度字段之前所有定长字段的总字节数),长度字段本身 4 字节,`lengthAdjustment`/`initialBytesToStrip` 均为 0(交给业务编解码器自己按偏移量解析消息头,而不是解码器直接剥离长度字段)。`maxFrameLength` 使用 `Integer.MAX_VALUE` 作为设计文档给出的默认值(实际生产使用时应按业务消息体大小设置更合理的上限,本次先遵循设计文档)。

5. **请求-响应匹配与超时竞态处理**
   `pendingRequests: ConcurrentHashMap<Long, CompletableFuture<Object>>` 是全局状态,发送请求时 `put`,收到响应或超时任务触发时都调用 `remove()`——`ConcurrentHashMap.remove()` 是原子操作,只有第一个成功拿到非 `null` 返回值的一方才能 `complete`/`completeExceptionally` 这个 Future,后到的一方拿到 `null` 直接跳过。这是设计文档中明确给出的竞态处理方案,本次直接采用,不做修改。

6. **服务方法注册与分发**
   服务端维护一个 `Map<String, Object>`(接口全限定名 → 服务实现实例)的服务注册表,`RpcServerHandler` 收到请求后按 `interfaceName` 查表拿到实现实例,再用反射按 `methodName` + `paramTypes` 找到方法并 `invoke`。注册表通过 `RpcServer` 提供的 `registerService(Class<?> interfaceClass, Object impl)` 方法在启动前手动注册(不做类路径扫描/注解自动发现,保持简单,可在后续改动中按需增加 `@RpcService` 注解 + 扫描)。

7. **心跳与空闲检测**
   服务端 `IdleStateHandler` 检测读空闲(一段时间没收到任何数据,包括心跳包),超时后关闭该连接;客户端启动定时任务按固定间隔发送 `HEARTBEAT` 类型消息。心跳间隔/超时时间采用设计文档未给出具体数值处,参考行业常见默认值(客户端心跳间隔 15 秒,服务端读空闲超时 30 秒,即容忍一次心跳丢失)。

8. **独立模块,不自动接入 Spring 容器**
   `RpcServer`/`NettyRpcClient` 提供纯 Java 的显式启动方式(不依赖 `@Component`/`@Bean` 自动装配),避免:(a) 单元测试/集成测试环境下意外启动额外的 Netty 端口监听;(b) 与仓库现有的 Nacos/MySQL/Redis 基础设施要求耦合。如果后续需要把 RPC 框架接入 Spring 生命周期(比如用 `@Bean` + `SmartLifecycle` 管理启动/停止),可以在使用方按需包装,不在本次改动范围内。

## Risks / Trade-offs

- **[Risk] 反射调用性能开销** → 服务端按方法名+参数类型反射查找并 `invoke`,比编译期生成的调用代码慢。Mitigation:当前定位是模板/演示代码,不追求极致性能;后续如需优化可以引入方法缓存(`Map<String, Method>`)减少重复的 `getMethod` 查找。
- **[Risk] `maxFrameLength = Integer.MAX_VALUE`** → 理论上允许单帧无限大,存在被恶意/异常客户端发送超大帧耗尽内存的风险。Mitigation:设计文档给出的是默认值,本次先遵循;在 proposal 中已注明这是"默认调优参数"而非生产最终值,后续接入具体业务时应按消息体实际大小收紧。
- **[Risk] Jackson JSON 序列化性能弱于 Kryo/Protobuf** → 吞吐量/延迟不如二进制序列化方案。Mitigation:`RpcSerializer` 接口做了插拔设计,后续可以无侵入替换实现,不影响协议层和客户端/服务端主体逻辑。
- **[Risk] 服务注册表无注解自动扫描,需手动 `registerService`** → 使用方需要显式调用注册方法,忘记注册会导致调用返回"服务未找到"。Mitigation:该行为在设计上是显式优于隐式的取舍,错误信息会明确指出未找到的 `interfaceName`,便于排查。
- **[Trade-off] 不接入真实 Nacos 服务发现** → `ServiceDiscovery` 默认只支持静态地址列表,生产环境需要使用方自行实现动态服务发现。Mitigation:接口已按设计文档的 `onServiceListChanged` 模式设计,后续接入 Nacos 只需新增一个实现类。

## Migration Plan

全新模块,无需数据迁移或灰度发布策略。落地步骤:
1. `build.gradle` 新增 Netty 依赖。
2. 按 `netty-rpc-protocol` → `netty-rpc-client`/`netty-rpc-server` 的依赖顺序实现代码(协议层是客户端和服务端的公共基础)。
3. 编写端到端测试(内嵌启动服务端 + 客户端发起真实请求),验证同步调用、异步调用、超时、负载均衡基本可用。
4. 本次改动不修改任何现有配置文件/现有代码路径,可随时通过删除 `com.example.template.rpc` 包整体回滚,不影响仓库其它部分。

## Open Questions

(无 —— 关键技术选型已在 Decisions 中给出结论,如有后续需求变化可在实现过程中通过 `openspec-update-change` 修订本设计。)
