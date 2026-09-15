## 1. 依赖与基础包结构

- [x] 1.1 在 `build.gradle` 中新增 Netty 依赖(`io.netty:netty-all`),补充必要的版本注释说明选型原因
- [x] 1.2 创建 `com.example.template.rpc` 及其子包骨架:`protocol`(协议与序列化)、`client`、`server`、`loadbalancer`、`discovery`

## 2. 协议层(netty-rpc-protocol)

- [x] 2.1 定义 `RpcMessage`/`RpcRequest`/`RpcResponse` 消息模型类(含 requestId、消息类型、接口名、方法名、参数类型、参数值、返回结果、异常信息等字段),补充类级注释
- [x] 2.2 定义 `RpcSerializer` 接口(序列化/反序列化字节数组),提供基于 Jackson 的默认实现 `JacksonRpcSerializer`
- [x] 2.3 实现自定义帧编码器 `RpcMessageEncoder`(将 `RpcMessage` 编码为:魔数 4B + 版本 1B + 消息类型 1B + 序列化方式 1B + requestId 8B + 消息体长度 4B + 消息体)
- [x] 2.4 实现自定义帧解码器 `RpcMessageDecoder`(解析定长消息头各字段,校验魔数,还原为 `RpcMessage`),内部或前置一个基于 `LengthFieldBasedFrameDecoder` 参数配置(maxFrameLength=Integer.MAX_VALUE, lengthFieldOffset=15, lengthFieldLength=4, lengthAdjustment=0, initialBytesToStrip=0)的粘包半包处理组件
- [x] 2.5 编写协议层单元测试:验证编码后再解码可还原原始消息、验证粘包(一次读取多帧)/半包(一帧拆两次到达)场景下解码器行为正确、验证非法魔数时连接被关闭

## 3. 负载均衡与服务发现

- [x] 3.1 定义 `LoadBalancer` 接口(输入可用 Channel 列表与请求,返回选中的 Channel),实现默认的 `RoundRobinLoadBalancer`;无可用连接时抛出明确异常
- [x] 3.2 定义 `ServiceDiscovery` 接口(维护可用连接列表、`onServiceListChanged` 更新入口),实现基于静态地址列表的默认实现 `StaticAddressServiceDiscovery`
- [x] 3.3 编写 `RoundRobinLoadBalancer` 单元测试,验证多次调用均匀轮询各个连接

## 4. 客户端(netty-rpc-client)

- [x] 4.1 实现 `NettyRpcClient`:维护 `pendingRequests`(`ConcurrentHashMap<Long, CompletableFuture<Object>>`)、`HashedWheelTimer` 超时任务、发送请求(经 `LoadBalancer` 选择 Channel 后写出,写失败时立即使 Future 失败)、`handleResponse` 完成 Future(利用 `remove()` 的原子性避免与超时任务竞态重复 complete)
- [x] 4.2 实现客户端入站 Handler,收到 `RESPONSE` 类型消息后回调 `NettyRpcClient.handleResponse`
- [x] 4.3 实现 requestId 生成器(如基于 `AtomicLong`/雪花算法均可,保证单客户端进程内唯一)
- [x] 4.4 实现 `RpcClientProxy`(JDK 动态代理),`invoke` 方法组装 `RpcRequest` 并调用 `NettyRpcClient` 发送,同步接口 `future.get(timeout)` 阻塞返回结果并转换超时异常
- [x] 4.5 暴露异步调用入口(直接返回 `CompletableFuture<Object>`,不内部阻塞 `get()`)
- [x] 4.6 实现客户端心跳定时任务(按配置间隔发送 `HEARTBEAT` 类型消息)
- [x] 4.7 编写客户端相关单元测试:请求-响应正常匹配完成 Future、响应晚于超时到达时被正确丢弃(不重复 complete)、写入失败时 Future 立即失败

## 5. 服务端(netty-rpc-server)

- [x] 5.1 实现 `RpcServer` 启动逻辑:`bossGroup`/`workerGroup` 均使用显式线程数的 `NioEventLoopGroup(n)` 构造(不使用无参默认构造),按设计文档配置 `ServerBootstrap`
- [x] 5.2 实现业务线程池(核心线程 16、最大线程 32、keepAlive 60s、`LinkedBlockingQueue(1000)`、`CallerRunsPolicy`),作为 `RpcServer` 的一个独立字段而非临时创建
- [x] 5.3 实现服务注册表与 `registerService(Class<?> interfaceClass, Object impl)` 方法
- [x] 5.4 实现 `RpcServerHandler`:收到解码后的 `RpcRequest` 提交到业务线程池,通过反射按接口名+方法名+参数类型在注册表中查找并调用实现,封装成功/失败 `RpcResponse` 后 `ctx.writeAndFlush`;接口未注册时返回明确的失败响应而不是抛未处理异常
- [x] 5.5 在服务端 pipeline 中加入 `IdleStateHandler` 检测读空闲,超时后关闭连接
- [x] 5.6 实现 `shutdownGracefully()`:标记停机状态 → 业务线程池 `shutdown()` 并等待超时 → 超时则 `shutdownNow()` → 关闭 `bossGroup`/`workerGroup`
- [x] 5.7 编写服务端相关单元测试:显式线程数配置生效、未注册服务返回失败响应而非抛异常、业务线程池队列打满时触发 `CallerRunsPolicy`

## 6. 端到端示例与验证

- [x] 6.1 定义一个简单示例接口(如 `EchoRpcService`)及其服务端实现,用于端到端联调
- [x] 6.2 编写端到端集成测试:内嵌启动 `RpcServer` + 通过 `RpcClientProxy` 发起同步调用,验证请求-响应正确;发起异步调用验证 `CompletableFuture` 正确完成;模拟服务端不响应验证客户端超时异常
- [x] 6.3 运行 `./gradlew test`(或按 CLAUDE.md 指定单测命令跑本模块相关测试类),确认新增代码与既有测试均通过

## 7. 代码规范与文档收尾

- [x] 7.1 按仓库要求为每个新增 Java 类补充类级注释,为非平凡方法(核心业务逻辑/非显而易见的控制流)补充方法注释与必要的行内注释,风格参考 `MybatisPlusConfig`/`TraceIdFilter`/`RedisConfig`
- [x] 7.2 自查代码风格(4 空格缩进、K&R 大括号、小驼峰命名、UTF-8 编码等)符合 `java-code-style` skill 规范
- [x] 7.3 确认本次改动未修改任何现有配置文件行为,`./gradlew build` 整体可通过
