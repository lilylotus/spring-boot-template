## ADDED Requirements

### Requirement: 自定义二进制协议帧结构
系统 SHALL 使用自定义二进制协议编码 RPC 消息,帧结构依次为:魔数(4 字节)、协议版本(1 字节)、消息类型(1 字节:请求/响应/心跳)、序列化方式标识(1 字节)、requestId(8 字节)、消息体长度(4 字节)、消息体(变长字节)。

#### Scenario: 编码请求消息为二进制帧
- **WHEN** 客户端把一个 `RpcRequest` 对象交给编码器编码
- **THEN** 输出的字节流依次包含魔数、版本号、消息类型(REQUEST)、序列化方式标识、requestId、消息体长度、序列化后的消息体字节

#### Scenario: 拒绝非法魔数的帧
- **WHEN** 服务端或客户端从连接上读取到的帧魔数与协议约定的魔数不一致
- **THEN** 系统 SHALL 关闭该连接并记录错误日志,不尝试继续解析后续字段

### Requirement: 粘包半包处理
系统 SHALL 使用基于长度字段的帧解码器(`LengthFieldBasedFrameDecoder`)按消息体长度字段拆分 TCP 字节流,保证上层业务 Handler 收到的始终是一个完整的协议帧,不出现半包或多帧粘连。

#### Scenario: 一次 TCP 读事件包含多个完整帧
- **WHEN** 底层 Channel 一次 `read` 事件中收到的字节恰好包含两个完整的协议帧
- **THEN** 帧解码器 SHALL 依次产出两个独立的完整帧,分别交给后续的消息解码器和业务 Handler 处理

#### Scenario: 一个协议帧被拆分到多次 TCP 读事件
- **WHEN** 一个完整协议帧的字节被底层 TCP 拆分到两次 `read` 事件中到达
- **THEN** 帧解码器 SHALL 缓存第一次收到的不完整字节,直到第二次读事件补全整帧长度后才产出该帧,业务 Handler 不会收到不完整的数据

### Requirement: 可插拔序列化接口
系统 SHALL 提供一个序列化接口(`RpcSerializer`),用于把 `RpcRequest`/`RpcResponse` 等消息体对象与字节数组相互转换,且默认实现使用 Jackson 完成 JSON 序列化/反序列化。

#### Scenario: 使用默认 Jackson 实现序列化请求体
- **WHEN** 客户端需要把一个 `RpcRequest` 对象写入协议帧的消息体
- **THEN** 系统 SHALL 调用配置的 `RpcSerializer` 实现(默认为 Jackson JSON 实现)将其序列化为字节数组

#### Scenario: 替换为自定义序列化实现
- **WHEN** 使用方提供了自定义的 `RpcSerializer` 实现并通过协议帧中的序列化方式标识字段进行标记
- **THEN** 编解码器 SHALL 按该标识选择对应的序列化实现完成编解码,而不是硬编码只支持 Jackson
