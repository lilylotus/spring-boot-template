# Netty RPC 使用说明

`org.example.simple.rpc` 提供基于 Netty 4.2 的生产 RPC 框架：固定二进制头协议、JSON 与 Protostuff
双编码、同步与异步同源的调用生命周期、服务发现与负载均衡、有界准入与优雅停机。

## 快速开始

服务端注册本地接口并监听：

```java
ServiceRegistry registry = new ServiceRegistry();
registry.register("问候服务", GreetingService.class, new GreetingServiceImpl());

RpcServer server = new RpcServer(registry, RpcConfig.defaults());
server.start("0.0.0.0", 9090);
```

客户端直连调用（同步）：

```java
RpcClient client = new RpcClient();
client.connect("127.0.0.1", 9090);

String reply = client.invoke(
    "问候服务", "greet", String.class, new Class<?>[] {String.class}, new Object[] {"小明"});
```

异步调用返回 `CompletableFuture`，不调用 `get()` 也会按预算超时：

```java
CompletableFuture<String> future = client.invokeAsync(
    "问候服务", "greet", String.class, new Class<?>[] {String.class}, new Object[] {"小明"});

future.whenComplete((value, error) -> log.info("结果：{}", value, error));
```

同步 `invoke` 内部委托同一套异步机制。框架线程（名称以 `rpc-` 开头）内禁止调用同步入口，
耗时的用户回调应当交给自有执行器，否则会占满完成线程池。

## 序列化编码配置

编号 `1` 为 JSON（Jackson 3），编号 `2` 为 Protostuff。服务端默认同时启用两者，
客户端默认使用 JSON；响应始终沿用请求的编号，不匹配按协议错误处理。

客户端级默认编码：

```java
RpcClient client = new RpcClient(
    RpcConfig.defaults(),
    SerializerRegistry.defaults(),
    (byte) 2,                      // 默认改为 Protostuff
    null,                          // 不启用 TLS
    RoundRobinLoadBalancer::new);
```

单次调用覆盖编码，并可同时指定路由键、超时与重试：

```java
CallOptions options = new CallOptions(
    (byte) 2,      // 序列化编号
    3000,          // 本次调用总预算，单位毫秒
    "用户-42",      // 一致性哈希路由键，其他策略可为 null
    true,          // 声明幂等
    1);            // 最多重试一次

CompletableFuture<String> future = client.invokeAsync(
    "问候服务", "greet", String.class, new Type[] {String.class}, new Object[] {"小明"}, options);
```

只启用单一编码时收窄注册表，收到未启用编号的报文会直接关闭连接：

```java
SerializerRegistry jsonOnly = new SerializerRegistry(new JacksonJsonSerializer());
RpcServer server = new RpcServer(registry, config, jsonOnly, null);
```

## 支持类型与字段演进

Protostuff 使用显式本地 Schema，不做远端类型加载，受支持的类型如下：

| 类别 | 支持范围 |
| --- | --- |
| 基础类型 | 全部原始类型及其包装类型 |
| 文本与二进制 | `String`、`char`/`Character`、`byte[]` |
| 数值扩展 | `BigDecimal`、`BigInteger` |
| 枚举 | 任意枚举，按名称编码 |
| 日期时间 | `java.util.Date`（毫秒时间戳）、`java.time.LocalDateTime`（ISO 文本） |
| 集合 | 声明了明确元素类型的 `List`、`Set`、数组 |
| 映射 | 声明了明确键值类型的 `Map` |
| 协议信封 | `RpcRequest`、`RpcResponse`、`RpcPayload`、`RpcError`，使用固定字段编号 |
| 业务 DTO | 具备无参构造、直接继承 `Object`、所有字段显式 `@Tag` 的普通类 |

以下情况在发送前就会失败，不会静默降级为 JSON：

- `Object`、接口、抽象类以及未适配的 `java.*` 类型
- 未声明 `@Tag` 或字段编号重复的 DTO
- 协议包之外的 `record`（需要显式适配器）
- 缺少泛型参数的集合与映射
- 循环对象图、嵌套深度超过 64 层、集合元素超过 10000 个

泛型集合需要通过 `Type` 入口传入实际类型：

```java
Type listType = new TypeToken<List<OrderDto>>() { }.getType();   // 或取自字段的 getGenericType()
serializer.serialize(orders, listType);
```

字段演进规则：

- 字段编号只增不复用；删除字段后其编号必须永久保留不再分配。
- 新增字段对旧版本对端不可见，旧端按类型默认值处理，不会解码失败。
- 修改字段类型等同于不兼容变更，必须换用新编号。
- JSON 侧保留未知字段兼容；JSON 信封中的 `byte[]` 以 Base64 表示，体积计入内存预算。

## 服务发现与负载均衡

内存发现适用于本地与集成测试：

```java
InMemoryServiceDiscovery discovery = new InMemoryServiceDiscovery();
discovery.update("问候服务", List.of(new ServiceInstance("a", "10.0.0.1", 9090, 1)));
client.subscribe("问候服务", discovery);
```

Nacos 注册与发现，地址与凭证全部来自外部配置：

```java
Properties properties = new Properties();
properties.put("serverAddr", System.getenv("NACOS_SERVER_ADDR"));
properties.put("namespace", System.getenv("NACOS_NAMESPACE"));
properties.put("username", System.getenv("NACOS_USERNAME"));
properties.put("password", System.getenv("NACOS_PASSWORD"));

NacosServiceDiscovery discovery = new NacosServiceDiscovery(properties, "DEFAULT_GROUP", "v1");
server.startRegistered("0.0.0.0", 9090, "10.0.0.1", discovery, "问候服务");
client.subscribe("问候服务", discovery);
```

服务端先绑定成功再注册，注册失败会回滚整个启动过程。发现语义：

- 有效空快照立即摘除全部实例，与订阅异常是不同语义。
- 订阅失败后仍可使用已有健康连接，缓存有效期 30 秒，过期后拒绝新调用。
- 实例被移除后，迟到的连接成功回调不会让它复活。
- 单条连接断开只影响该连接上的在途请求，其余实例继续提供服务。

负载均衡策略通过构造参数选择：`RoundRobinLoadBalancer`（默认轮询）、
`WeightedRoundRobinLoadBalancer`（平滑加权，过滤非正权重）、
`ConsistentHashLoadBalancer`（一致性哈希，要求调用显式提供路由键）。
候选集合只包含活跃、可写且仍有在途额度的连接。

## TLS 与 mTLS

```java
SslContext serverTls = SslContextBuilder.forServer(keyManagerFactory)
    .trustManager(trustManagerFactory)
    .clientAuth(ClientAuth.REQUIRE)
    .build();
SslContext clientTls = SslContextBuilder.forClient()
    .trustManager(trustManagerFactory)
    .keyManager(keyManagerFactory)
    .build();

RpcServer server = new RpcServer(registry, config, SerializerRegistry.defaults(), serverTls);
RpcClient client = new RpcClient(
    config, SerializerRegistry.defaults(), (byte) 1, clientTls, RoundRobinLoadBalancer::new);
client.connect("service.internal", 9090);
```

客户端按连接时使用的主机名执行标准主机名校验，因此必须使用证书中的名称连接，
不能用 IP 绕过。不受信任的证书会直接握手失败；握手时间受 `handshakeTimeoutMillis` 约束。

## 监控与链路

指标通过 Micrometer 全局注册表发布，标签只使用受控的 `side` 与 `outcome`，
不包含 requestId、实例地址等高基数值：

| 指标 | 含义 |
| --- | --- |
| `rpc.calls` | 按端与终态分类的调用计数 |
| `rpc.duration` | 按端与终态分类的调用耗时 |
| `rpc.pending` | 当前在途请求数 |
| `rpc.connections` | 当前活跃连接数 |
| `rpc.queue` | 编解码与业务队列深度 |
| `rpc.buffered.bytes` | 框架持有的报文字节 |
| `rpc.allocator.direct.bytes` | 池化分配器直接内存占用 |
| `rpc.discovery.update`、`rpc.reconnect`、`rpc.heartbeat.timeout` | 发现变更、重连与心跳超时事件 |

链路使用 OpenTelemetry W3C 上下文，在客户端发起与服务端执行处建立 span，
异步线程切换时显式恢复上下文。链路载体只携带 `traceparent`/`tracestate`，
不包含调用参数；采集异常不会影响调用结果。

## 重试语义

默认不重试。只有显式声明幂等的调用才能配置最多一次重试：

- 候选仅限连接类失败（`CONNECTION_CLOSED`）；超时、业务错误、过载拒绝与取消都不重试。
- 重试共享同一个逻辑调用截止时间，预算耗尽即返回超时，不会无限延长。
- 退避在独立调度线程等待，取消会立即阻止尚未发出的重试。
- 写失败可能已经到达服务端，幂等键与持久化去重仍由业务负责。

## 生产参数与资源预算

所有参数通过 `RpcConfig.builder()` 覆盖，必须为正整数；线程数为固定默认值，
不按 CPU 核数推导，传入 `0` 或负值会在构建时失败。

```java
RpcConfig config = RpcConfig.builder()
    .serverWorkerThreads(8)
    .businessThreads(16)
    .businessQueueCapacity(512)
    .requestsPerSecond(20000)
    .burstCapacity(2000)
    .build();
```

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `serverBossThreads` | 1 | 服务端监听线程 |
| `serverWorkerThreads` | 4 | 服务端 I/O 线程 |
| `clientIoThreads` | 2 | 客户端 I/O 线程 |
| `businessThreads` / `businessQueueCapacity` | 8 / 256 | 业务池与有界队列 |
| `codecThreads` / `codecQueueCapacity` | 2 / 256 | 编解码池与有界队列 |
| `completionThreads` | 2 | 客户端完成与回调线程 |
| `maxPendingRequests` / `maxRequestsPerConnection` | 1024 / 128 | 客户端在途上限 |
| `maxServerConnections` / `maxClientConnections` | 1024 / 128 | 两端连接上限 |
| `backlog` | 1024 | 监听积压队列 |
| `connectTimeoutMillis` / `writeTimeoutMillis` | 1000 / 1000 | 建连与单次写入超时 |
| `requestTimeoutMillis` | 5000 | 单次调用总预算 |
| `handshakeTimeoutMillis` | 3000 | TLS 握手超时 |
| `heartbeatIntervalMillis` / `readIdleTimeoutMillis` | 15000 / 45000 | 写空闲心跳与读失活 |
| `drainTimeoutMillis` / `shutdownTimeoutMillis` | 30000 / 5000 | 排空与资源关闭预算 |
| `maxMessageLength` | 8388608 | 单个消息体上限，帧上限再加 19 字节固定头 |
| `writeLowWaterMark` / `writeHighWaterMark` | 32768 / 65536 | 写缓冲水位 |
| `maxChannelWriteBytes` | 16777216 | 单连接待写字节硬上限 |
| `maxBufferedBytes` | 67108864 | 每实例报文字节预算 |
| `requestsPerSecond` / `burstCapacity` | 1000 / 200 | 单实例每服务令牌桶 |

需要注意的边界：

- 水位只改变 `Channel.isWritable()`，不是内存硬上限；真正的硬上限是
  `maxChannelWriteBytes` 与 `maxBufferedBytes`，超出即拒绝并关闭连接。
- 默认限流为**单实例每服务 1000 次/秒、突发 200 次**，超过即返回稳定的过载错误。
  高吞吐部署必须显式调高，否则会把正常流量当作过载拒绝。
- `maxBufferedBytes` 是框架持有的逻辑报文预算，不等于进程内存。JVM 堆、直接内存、
  线程栈、反序列化后的对象以及 Nacos SDK 都需要另外预算。
- 参数组合在构建时校验：低水位必须小于高水位、高水位不得超过单连接硬上限、
  单连接硬上限与端级预算都必须至少容纳一个完整帧、心跳间隔必须小于读失活阈值。
- 连接选项在安装管线前会回读校验，任何未生效的选项都会直接失败，不会被静默忽略。
- 泄漏检测沿用进程已有配置，生产基线使用 `SIMPLE`，专项验证时用
  `-Dio.netty.leakDetection.level=PARANOID` 启动。
- 日志只记录脱敏后的框架配置与错误类别，调用参数、凭证与完整堆栈只留在本地日志。

## 验证入口

```bash
# 全量单元与集成测试
gradlew.bat test

# 压力与故障验证，结果写入 build/reports/rpc-stress.md
gradlew.bat test --tests "org.example.simple.rpc.RpcStressVerificationTest" -Drpc.stress=true

# Nacos 集成验证，需要外部 Nacos 实例
set RPC_NACOS_SERVER_ADDR=127.0.0.1:8848
gradlew.bat test --tests "org.example.simple.rpc.NacosDiscoveryVerificationTest"
```

未设置 `RPC_NACOS_SERVER_ADDR` 时 Nacos 验证会被跳过并在报告中记为 skipped，
不会被当作已验证通过。

### 压力验证结果

环境：Java 21、12 核、最大堆 512 MiB、回环网络单连接。

| 场景 | 结果 |
| --- | --- |
| 稳态吞吐（8 线程，12000 次调用，限流调高至 100 万/秒） | 13474 次/秒，p50 0.483 ms，p95 1.273 ms，p99 1.845 ms，错误率 0 |
| 饱和拒绝（业务线程 1、队列 1，并发提交 400） | 受理 2，服务端过载拒绝 126，客户端准入拒绝 272，无一悬挂 |
| 最大帧并发（40 × 约 300 KB 响应） | 全部完成，服务端报文占用峰值 6.0 MiB，低于 32 MiB 端级预算 |

三个场景结束后客户端与服务端的报文字节占用均回到 0，在途请求数为 0，
说明准入、编解码、写入与断连路径的资源释放是成对的。

## 从旧协议迁移

版本一协议与早期的四字节长度前缀 JSON 协议不兼容，且请求标识由字符串改为 `long`，
不提供自动兼容。迁移方式：

1. 新版本使用独立端口与独立服务版本（Nacos 的 `服务名:版本`）注册，与旧版本并行运行。
2. 客户端整组切换到新服务版本，观察指标与错误类别。
3. 失败时整组退回旧版本；不要在同一端口上混用两种协议。

旧实现中基于 Netty `Promise` 的调用方式已被 `CompletableFuture` 取代，
同步入口保留但内部委托异步机制，业务代码通常无需改写调用形式。
