## Why

仓库目前只有基于 OpenFeign + HTTP 的服务间调用方式,没有一套基于 Netty 的自定义 RPC 框架可供参考/演示。`Java_RPC_Netty设计方案.md` 已经给出了完整的设计思路(自定义二进制协议、动态代理、`CompletableFuture` 异步核心、超时时间轮、可插拔负载均衡、IO 线程与业务线程池分离、优雅停机),需要把这份设计落地成本仓库中的一个独立、可运行、可被其它服务复用的 RPC 框架模块,并作为"高性能服务端线程模型"配置的范例(尤其是 Netty `bossGroup`/`workerGroup` 必须显式指定线程数,避免默认使用 CPU 核心数导致线程池过大)。

## What Changes

- 新增独立的 Netty RPC 框架模块 `com.example.template.rpc`,包含协议编解码、客户端、服务端三大部分,可被本仓库或未来 fork 出去的实际业务服务直接引用。
- **协议层**:自定义二进制协议(魔数 + 版本 + 消息类型 + 序列化方式 + requestId + 消息长度 + 消息体),基于 `LengthFieldBasedFrameDecoder`/`LengthFieldPrepender` 解决 TCP 粘包半包。
- **序列化**:定义可插拔 `RpcSerializer` 接口,默认提供基于 Jackson 的 JSON 实现(与本仓库 `RedisConfig` 已使用的序列化技术栈保持一致,避免引入 Kryo/Protostuff 带来的额外反序列化风险面和新依赖);预留扩展点,后续可按需替换为 Protostuff/Kryo/Protobuf 实现。
- **客户端**:`RpcClientProxy`(JDK 动态代理,把接口调用转成 RPC 请求)+ `NettyRpcClient`(requestId → `CompletableFuture` 的全局映射表、`HashedWheelTimer` 超时检测、`ConcurrentHashMap.remove()` 原子操作防止超时与正常响应竞态重复 complete),同时暴露同步阻塞调用(`future.get(timeout)`)和异步调用(直接返回 `CompletableFuture`)两种方式。
- **负载均衡**:`LoadBalancer` 可插拔接口,默认提供轮询(`RoundRobinLoadBalancer`)实现;可用连接列表来自可插拔的 `ServiceDiscovery` 接口,默认提供基于静态地址列表的实现(不接入 Nacos 真实服务发现,接口本身按 Nacos 等注册中心可扩展的方式设计)。
- **服务端**:`RpcServer` 负责启动 Netty(`bossGroup`/`workerGroup` **必须显式传入线程数**,不能使用无参默认构造导致的 `CPU核心数*2` 线程池),`RpcServerHandler` 收到请求后提交到独立的业务线程池(`ThreadPoolExecutor` + `LinkedBlockingQueue` + `CallerRunsPolicy`)执行,避免阻塞 EventLoop;通过反射/注册表机制按接口名+方法名分发到具体服务实现类。
- **连接保活与优雅停机**:服务端基于 `IdleStateHandler` 检测空闲连接并关闭,客户端定时发送心跳包;服务端提供 `shutdownGracefully()`,停止接收新连接 → 等待业务线程池排空(超时强制中断)→ 关闭 `bossGroup`/`workerGroup`。
- 新增 Netty 依赖(`build.gradle`),其余参数(帧解码器参数、业务线程池核心/最大线程数与队列容量、心跳间隔、连接超时等)采用设计文档给出的默认调优值。
- 提供示例:一个简单的 RPC 服务接口 + 服务端实现 + 客户端调用示例,验证端到端可用性(不依赖真实 MySQL/Redis/Nacos 基础设施,可独立运行)。

## Capabilities

### New Capabilities
- `netty-rpc-protocol`: 自定义二进制协议编解码(魔数/版本/消息类型/序列化方式/requestId/长度 + 帧解码器),以及可插拔序列化接口。
- `netty-rpc-client`: 基于动态代理 + `CompletableFuture` + 超时时间轮的 RPC 客户端,支持同步/异步调用、负载均衡选择连接、请求-响应匹配与竞态处理。
- `netty-rpc-server`: 基于 Netty 的 RPC 服务端,IO 线程与业务线程池分离,显式指定线程数,心跳保活与优雅停机,服务方法注册与分发。

### Modified Capabilities
(无 —— 不改动仓库中任何已有 spec 的既有需求)

## Impact

- **新增代码**:`src/main/java/com/example/template/rpc/**`(protocol、client、server、loadbalancer、discovery、serializer 等子包),`src/test/java/com/example/template/rpc/**` 的端到端测试。
- **`build.gradle`**:新增 `io.netty:netty-all`(或按需拆分为 `netty-handler`/`netty-codec` 等模块)依赖。
- **不影响**现有的 Web/OpenFeign/MyBatis/Redis/日志等既有基础设施配置,RPC 框架作为独立模块,默认不随 Spring Boot 应用自动启动(通过独立的 `main` 方法或按需注入的 `@Bean` 显式启动,避免影响本地无 Netty 端口占用场景下的其它测试)。
- **文档**:`Java_RPC_Netty设计方案.md` 保留作为设计参考,不做修改。
