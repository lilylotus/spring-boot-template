## Purpose

提供可测试的 RPC 行为契约，使 Java 客户端能够通过有边界的消息协议调用服务端注册服务，并获得成功结果或结构化错误。

## ADDED Requirements

### Requirement: 请求与响应关联

每个 RPC 请求 MUST 携带唯一请求标识、服务名、方法名、参数类型和参数值；每个响应 MUST 回传相同的请求标识，并明确表示成功或失败。

#### Scenario: 成功响应匹配请求

- **WHEN** 客户端发送一个合法请求且服务端成功执行目标方法
- **THEN** 服务端返回相同请求标识、成功状态及方法结果

#### Scenario: 失败响应匹配请求

- **WHEN** 服务端无法处理一个具有合法请求标识的请求
- **THEN** 服务端返回相同请求标识、失败状态及非空错误信息

### Requirement: 响应结果使用 JSON 字符串

成功响应的 `result` MUST 是服务端使用 Jackson 将方法返回值序列化得到的 JSON 字符串，而不是嵌套 JSON 节点。客户端 MUST 使用 Jackson 将该字符串反序列化为调用方声明的返回类型。失败响应的 `result` MUST 为空。

#### Scenario: 返回普通对象

- **WHEN** 服务方法成功返回一个非空 Java 对象
- **THEN** 响应 `result` 包含该对象的 Jackson JSON 字符串，客户端将其还原为声明类型

#### Scenario: 返回字符串

- **WHEN** 服务方法成功返回 Java 字符串
- **THEN** 响应 `result` 包含带 JSON 引号及必要转义的字符串文本，客户端获得原始 Java 字符串

#### Scenario: 返回空值

- **WHEN** 服务方法成功返回 `null`
- **THEN** 响应 `result` 为 JSON 文本 `null`，且响应保持成功状态

#### Scenario: 返回值无法序列化

- **WHEN** Jackson 无法序列化服务方法返回值
- **THEN** 服务端返回 `SERIALIZATION_FAILED` 类型的失败响应，不发送部分结果

### Requirement: 有边界的 JSON 消息传输

系统 MUST 使用 JSON 表示请求和响应，并以明确的消息长度边界在 TCP 连接上传输完整消息。系统 MUST 拒绝超过配置上限或无法反序列化的消息，而不得将其交给服务调用逻辑。

#### Scenario: 拆分到多个网络数据包的消息

- **WHEN** 一条合法 JSON 消息分多次到达接收端
- **THEN** 接收端仅在完整消息到达后解码并处理一次

#### Scenario: 一次到达多条消息

- **WHEN** 多条合法 JSON 消息在同一批网络数据中到达
- **THEN** 接收端按消息边界分别解码并按顺序处理

#### Scenario: 消息超过上限

- **WHEN** 接收端读取到声明长度超过配置上限的消息
- **THEN** 接收端拒绝该消息并关闭或标记当前连接不可继续使用

### Requirement: JSON 兼容性与日期时间格式

系统 MUST 在 JSON 反序列化时忽略目标 Java 类型中不存在的字段。`java.util.Date` 和 `LocalDateTime` 的 JSON 文本表示 MUST 使用 `yyyy-MM-dd HH:mm:ss` 格式；未显式指定时区时 MUST 保留运行 JVM 的默认时区行为。

#### Scenario: 反序列化包含未知字段的对象

- **WHEN** JSON 对象包含目标 Java 类型未声明的字段
- **THEN** 系统忽略未知字段并成功填充所有可识别字段

#### Scenario: 序列化日期时间值

- **WHEN** 系统序列化 `java.util.Date` 或 `LocalDateTime` 值
- **THEN** 输出日期时间文本符合 `yyyy-MM-dd HH:mm:ss` 格式

#### Scenario: 反序列化日期时间文本

- **WHEN** 系统读取符合 `yyyy-MM-dd HH:mm:ss` 格式的日期时间文本
- **THEN** 文本成功转换为目标 `java.util.Date` 或 `LocalDateTime` 值

#### Scenario: 读取不符合格式的日期时间文本

- **WHEN** 系统读取不符合 `yyyy-MM-dd HH:mm:ss` 格式的日期时间文本
- **THEN** 反序列化以 `SERIALIZATION_FAILED` 类型的 RPC 异常结束

### Requirement: 服务注册与方法分发

服务端 MUST 只调用已注册服务上的公开方法，并根据请求中的服务名、方法名和参数类型定位目标方法。未注册服务、未知方法或参数不匹配 MUST 产生失败响应。

#### Scenario: 调用已注册服务

- **WHEN** 请求引用已注册服务及参数匹配的公开方法
- **THEN** 服务端调用该方法并将返回值写入成功响应

#### Scenario: 调用未知服务

- **WHEN** 请求引用未注册的服务名
- **THEN** 服务端不执行任何业务方法并返回失败响应

#### Scenario: 业务方法抛出异常

- **WHEN** 已注册服务的方法在执行期间抛出异常
- **THEN** 服务端返回失败响应，且服务端继续处理后续合法请求

### Requirement: 客户端调用生命周期

客户端 MUST 支持连接指定服务端、发起 RPC 调用、按请求标识等待对应响应，并在配置的超时时间内返回结果或抛出调用异常。连接关闭时，所有未完成调用 MUST 以异常结束。

#### Scenario: 在超时前收到响应

- **WHEN** 客户端在配置超时前收到与请求标识匹配的成功响应
- **THEN** 调用方获得反序列化后的方法结果

#### Scenario: 调用超时

- **WHEN** 客户端在配置超时内未收到匹配响应
- **THEN** 调用以超时异常结束，并清理该请求的等待状态

#### Scenario: 收到失败响应

- **WHEN** 客户端收到与请求标识匹配的失败响应
- **THEN** 调用以包含服务端错误信息的 RPC 异常结束

### Requirement: 客户端异步响应调度

客户端 MUST 使用独立的 Netty `DefaultEventLoop` 和 `Promise` 管理待完成调用。网络 I/O 处理器收到响应后 MUST 将匹配与完成操作异步提交到该事件循环，不得在网络 I/O 线程中阻塞等待或转换调用结果。

#### Scenario: 异步完成匹配响应

- **WHEN** 网络 I/O 处理器收到具有已登记请求标识的响应
- **THEN** 独立 `DefaultEventLoop` 从待完成调用表移除对应 `Promise` 并完成响应

#### Scenario: 并发乱序响应

- **WHEN** 多个响应以不同于请求发送顺序的次序到达
- **THEN** 每个 `Promise` 仅由相同请求标识的响应完成

#### Scenario: 在响应事件循环发起同步调用

- **WHEN** 代码尝试在客户端响应 `DefaultEventLoop` 线程中执行同步 RPC 调用
- **THEN** 客户端立即拒绝该调用以避免事件循环死锁

#### Scenario: 客户端关闭响应事件循环

- **WHEN** 客户端关闭且仍存在未完成调用
- **THEN** 响应事件循环先以连接关闭异常完成全部 `Promise`，随后释放自身线程资源

### Requirement: 资源可控关闭

客户端和服务端 MUST 提供幂等关闭能力，释放连接、事件循环线程和未完成调用资源。

#### Scenario: 重复关闭

- **WHEN** 调用方对已关闭的客户端或服务端再次执行关闭操作
- **THEN** 操作安全完成且不产生新的资源或未处理异常
