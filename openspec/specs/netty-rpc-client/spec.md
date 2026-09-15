# netty-rpc-client Specification

## Purpose

为基于 Netty 的自研 RPC 框架提供客户端能力：通过动态代理让业务代码以普通接口调用的方式发起远程调用，支持同步阻塞与异步非阻塞两种调用方式，正确处理请求-响应匹配与超时竞态，并通过可插拔的负载均衡与服务发现维护到服务端的连接，同时以心跳维持长连接活跃。

## Requirements

### Requirement: 动态代理透明化接口调用
系统 SHALL 提供基于 JDK 动态代理的 `RpcClientProxy`,使业务代码通过普通接口方法调用即可发起 RPC 请求,无需感知底层网络细节。

#### Scenario: 通过代理对象发起同步调用
- **WHEN** 业务代码调用由 `RpcClientProxy.getProxy(接口Class)` 生成的代理对象上的某个接口方法
- **THEN** 系统 SHALL 将该方法调用转换为携带接口名、方法名、参数类型、参数值的 `RpcRequest`,发送给服务端并阻塞等待结果返回

#### Scenario: 同步调用超时抛出异常
- **WHEN** 代理对象发起的同步调用在配置的超时时间内未收到服务端响应
- **THEN** 系统 SHALL 抛出携带 requestId 信息的超时异常,而不是无限期阻塞

### Requirement: 异步调用能力
系统 SHALL 额外提供直接返回 `CompletableFuture<Object>` 的异步调用接口,使业务代码可以自行选择阻塞等待或非阻塞回调处理结果。

#### Scenario: 发起异步调用并注册回调
- **WHEN** 业务代码调用异步调用接口发起一次 RPC 请求
- **THEN** 系统 SHALL 立即返回一个未完成的 `CompletableFuture`,并在服务端响应到达后异步完成该 Future,业务代码可以通过 `thenAccept` 等方法注册回调而不阻塞当前线程

### Requirement: 请求-响应匹配与超时竞态处理
系统 SHALL 使用 requestId 到 `CompletableFuture` 的全局映射表关联每个请求与其响应,并使用基于时间轮(`HashedWheelTimer`)的超时任务检测未响应的请求;当正常响应和超时任务同时触发时,系统 SHALL 保证该请求对应的 Future 只被 complete 一次。

#### Scenario: 正常响应先于超时到达
- **WHEN** 服务端响应在超时时间内到达,且此时超时任务尚未触发
- **THEN** 系统 SHALL 从映射表中原子移除该 requestId 对应的 Future 并用响应结果完成它,随后触发的超时任务发现该 requestId 已不在映射表中,不做任何处理

#### Scenario: 超时任务先于响应触发
- **WHEN** 请求在超时时间内未收到响应,超时任务先被触发
- **THEN** 系统 SHALL 从映射表中原子移除该 requestId 对应的 Future 并以超时异常完成它;随后姗姗来迟的响应发现映射表中已不存在该 requestId,直接丢弃该响应,不再重复操作 Future

#### Scenario: 写入请求失败立即使调用失败
- **WHEN** 客户端向 Channel 写入请求时因连接已断开等原因写入失败
- **THEN** 系统 SHALL 立即以写入失败的异常完成对应的 Future,不必等待超时时间耗尽

### Requirement: 可插拔负载均衡选择连接
系统 SHALL 提供 `LoadBalancer` 接口,在发送每个请求前从当前可用连接列表中选择一个 Channel;默认实现为轮询策略。

#### Scenario: 轮询策略均匀分发请求
- **WHEN** 存在多个可用服务端连接,客户端连续发起多次请求
- **THEN** 默认的轮询负载均衡器 SHALL 依次轮流选择不同的可用连接,而不是每次都选择同一个连接

#### Scenario: 无可用连接时快速失败
- **WHEN** 当前可用连接列表为空
- **THEN** 负载均衡器 SHALL 抛出明确的异常,提示没有可用的服务端连接,而不是返回空值导致后续空指针错误

### Requirement: 可插拔服务发现维护连接列表
系统 SHALL 提供 `ServiceDiscovery` 接口用于维护负载均衡器可选的连接列表;默认实现基于启动时配置的静态地址列表建立并维护连接。

#### Scenario: 使用静态地址列表初始化连接
- **WHEN** 客户端按默认的服务发现实现启动,并配置了一组静态服务端地址
- **THEN** 系统 SHALL 依次与这些地址建立连接,并将建立成功的 Channel 加入可用连接列表供负载均衡器使用

### Requirement: 客户端心跳保活
系统 SHALL 定期向服务端发送心跳消息,以维持长连接活跃并配合服务端的空闲连接检测。

#### Scenario: 定时发送心跳
- **WHEN** 客户端与服务端的连接建立成功且处于空闲状态达到配置的心跳间隔
- **THEN** 系统 SHALL 主动发送一条心跳类型的协议帧给服务端
