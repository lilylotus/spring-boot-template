## 背景与原因

当前项目缺少可复用的远程过程调用能力，无法通过网络以统一契约调用服务端 Java 服务。新增基于 Netty 的 RPC 基础能力，可以为后续服务拆分和跨进程通信提供清晰、可扩展的起点。

## 变更内容

- 新增 RPC 公共组件，定义请求、响应、协议编解码、序列化接口及异常语义。
- 首期使用 Jackson JSON 完成消息序列化，并通过长度字段解决 TCP 粘包、拆包问题。
- Jackson 反序列化忽略目标类型中不存在的 JSON 字段，并统一日期时间文本格式为 `yyyy-MM-dd HH:mm:ss`。
- RPC 成功响应的 `result` 使用 Jackson 序列化后的 JSON 字符串，不再直接携带 `JsonNode`。
- 将 RPC 测试中的中文 Java 方法标识符改为英文，确保生产代码与测试代码统一遵循英文标识符约束。
- 新增 Netty RPC 服务端，支持服务注册、请求分发、方法调用及结果响应。
- 新增 Netty RPC 客户端，支持连接服务端、发送请求、匹配响应及同步调用。
- 客户端待完成调用使用 Netty `Promise` 保存，并由独立 `DefaultEventLoop` 异步完成服务端响应。
- 为协议编解码、序列化、服务调用和异常场景补充 JUnit 5 测试。
- 分别使用 `org.example.simple.rpc.common`、`org.example.simple.rpc.server` 和 `org.example.simple.rpc.client` 作为公共组件、服务端和客户端的包路径。

## 能力

### 新增能力

- `netty-json-rpc`：定义使用 Netty 传输、Jackson JSON 序列化的 RPC 请求响应协议，以及客户端调用和服务端处理行为。

### 修改能力

无。

## 影响范围

- 在 `src/main/java/org/example/simple/rpc` 下新增公共组件、服务端和客户端代码。
- 在 `src/test/java/org/example/simple/rpc` 下新增对应测试。
- 使用项目已有的 Netty、Jackson 和 JUnit 5 依赖，首期不引入额外序列化框架。
- `JacksonJsonSerializer` 的默认映射器及调用方传入映射器采用一致的兼容性和日期时间配置。
- `RpcResponse`、服务端结果生成及客户端结果转换同步采用字符串形式的 JSON 结果载荷。
- `RpcClient` 和响应处理器从 `CompletableFuture` 迁移到 Netty `DefaultEventLoop` 与 `Promise`。
- RPC 生产代码和测试代码均需通过中文 Java 类名、方法名和字段名扫描。
- RPC 服务的监听端口、远端地址及调用超时由调用方显式配置，不在代码中写入机器相关配置。
