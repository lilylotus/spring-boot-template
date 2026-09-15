## ADDED Requirements

### Requirement: 定时轮询未发送记录
系统 SHALL 提供一个独立于业务事务之外的定时任务,按固定间隔轮询 `outbox_event` 表中状态为 `PENDING` 的记录并尝试投递,轮询过程 SHALL 与产生这些记录的业务事务完全解耦。

#### Scenario: 定时任务按批次取出待发送记录
- **WHEN** 定时任务到达执行时机,且表中存在若干条 `PENDING` 状态的记录
- **THEN** 系统 SHALL 按配置的批次大小上限取出这些记录并逐条尝试投递

### Requirement: 投递成功后标记 SENT
系统 SHALL 在成功调用消息发布接口投递某条记录后,将该记录状态更新为 `SENT`。

#### Scenario: 投递成功
- **WHEN** 某条 `PENDING` 记录通过消息发布接口投递未抛出异常
- **THEN** 系统 SHALL 将该记录状态更新为 `SENT`,后续轮询 SHALL NOT 再次选中该记录

### Requirement: 投递失败重试与死信转换
系统 SHALL 在投递某条记录失败时将其 `retry_count` 加一;当 `retry_count` 超过配置的重试上限时,SHALL 将该记录状态更新为 `FAILED`,不再参与后续轮询;未超过上限的记录保持 `PENDING`,可在下一轮被再次尝试。

#### Scenario: 投递失败但未超过重试上限
- **WHEN** 某条记录投递失败,且失败后的 `retry_count` 未超过配置的重试上限
- **THEN** 系统 SHALL 保持该记录状态为 `PENDING`,`retry_count` 加一,该记录 SHALL 在下一轮轮询中被再次选中

#### Scenario: 投递失败且已超过重试上限
- **WHEN** 某条记录投递失败,且失败后的 `retry_count` 超过配置的重试上限
- **THEN** 系统 SHALL 将该记录状态更新为 `FAILED`,该记录 SHALL NOT 再被后续轮询选中

### Requirement: 可插拔消息发布接口
系统 SHALL 提供 `OutboxMessagePublisher` 接口用于将 Outbox 记录投递出去,默认实现基于 Spring 的 `ApplicationEventPublisher` 将投递动作转换为一个进程内事件,供下游消费者监听处理;该接口 SHALL 可被替换为对接真实消息队列的实现,替换实现 SHALL NOT 要求修改消息中继任务的调用方式。

#### Scenario: 默认实现将投递转换为进程内事件
- **WHEN** 消息中继任务调用默认的 `OutboxMessagePublisher` 实现投递一条记录
- **THEN** 系统 SHALL 发布一个携带该记录事件类型与内容的进程内事件,供已注册的监听器接收处理

### Requirement: 重试状态完全持久化,不依赖进程内存
系统 SHALL 保证消息中继任务的所有状态(待处理记录、重试次数、已发送/失败标记)都持久化在 `outbox_event` 表中,进程重启后 SHALL 能够从表中已有状态继续处理未完成的记录,不丢失待发送的事件。

#### Scenario: 进程重启后继续处理未完成记录
- **WHEN** 消息中继任务所在进程在处理完部分记录后重启,且表中仍存在 `PENDING` 状态的记录
- **THEN** 进程重启后的下一轮定时任务 SHALL 继续扫描到这些 `PENDING` 记录并尝试投递,不会因为进程重启而遗漏
