## Why

如果"更新业务数据"和"通知外部系统这件事发生了"分开做(比如先改数据库再直接调消息队列),中间进程崩溃会导致状态已改但通知没发出去、或者消息发出去了但事务却回滚。`Outbox模式事务流程.md` 给出了标准解法——把"发消息"这个动作本身也变成一次数据库写入,让它跟业务更新在同一个本地事务里原子提交,把"跨系统可靠性问题"降级为"单库事务原子性问题"。仓库目前没有任何示例演示这个模式,需要把设计文档落地成可运行、可测试的模板代码,供后续实际业务参考。

## What Changes

- 新增独立的 `com.example.template.outbox` 包,包含 Outbox 表结构、事务性写入示例、消息中继定时任务、可插拔消息发布接口、消费方幂等处理四个部分,复用仓库已有 MySQL + MyBatis-Plus + Jackson 技术栈,不引入新的持久化框架。
- **Outbox 表结构**:`outbox_event`(id、aggregate_id、event_type、payload、status[PENDING/SENT/FAILED]、create_time、retry_count),用 MyBatis-Plus 的 `BaseMapper` 实现,不需要手写 XML;建表 SQL 按仓库现有约定放在 `src/main/resources/sql/`(仅供本地手动建表参考,不接入自动迁移机制,与仓库当前 `batch-test.sql` 的用法一致)。
- **业务写入与 Outbox 写入同一事务**:复用设计文档里的请假审批例子——新增 `leave_request` 业务表 + `LeaveRequest` 实体,`ApprovalFinishedEvent` 领域事件,`@EventListener` + `@Transactional` 监听器里先更新 `leave_request` 状态、再写入 `outbox_event`,两次 `save` 同一事务提交,验证"要么都成功要么都回滚"。
- **消息中继(Message Relay)**:新增 `@Scheduled` 定时任务,按 `create_time` 顺序分批轮询 `PENDING` 状态的 outbox 记录,调用可插拔的消息发布接口投递;成功标记 `SENT`,失败则 `retry_count + 1`,超过重试上限(默认 10 次)标记 `FAILED`(死信,需人工介入);该任务失败重试完全依赖表里的持久化状态,不依赖内存,进程重启后未处理完的记录仍会被下一轮轮询继续处理。
- **可插拔消息发布接口**:定义 `OutboxMessagePublisher` 接口,仓库目前没有引入 Kafka/RabbitMQ 等真实 MQ 依赖,**不新增 MQ 基础设施依赖**——默认实现基于 Spring 自身的 `ApplicationEventPublisher`,把"投递"动作发布为一个进程内事件(`OutboxMessageDeliveredEvent`),用来演示"中继投递成功 → 下游消费方处理"的完整链路;该接口可以在需要接入真实 MQ 时被替换实现,不需要改动中继任务本身的轮询/重试逻辑。
- **消费方幂等处理**:新增 `processed_event` 表 + 对应 MyBatis-Plus Mapper,提供一个监听 `OutboxMessageDeliveredEvent` 的示例消费者,处理前先判断 `event_id` 是否已存在于 `processed_event`(存在则跳过),处理完成后落一条记录,演示"同一条消息被重复投递也不会重复执行业务逻辑"。
- **启用 Spring 定时任务**:仓库当前未启用 `@EnableScheduling`,本次改动新增该配置(消息中继任务依赖它)。

## Capabilities

### New Capabilities
- `outbox-event-store`: Outbox 事件表结构、实体、状态流转(PENDING/SENT/FAILED)与基础的持久化读写能力。
- `outbox-transactional-write`: 业务数据更新与 Outbox 记录写入在同一本地事务内原子提交的能力(以请假审批场景为示例)。
- `outbox-message-relay`: 独立于业务事务之外的定时消息中继任务,负责轮询未发送记录、调用可插拔发布接口投递、维护重试计数与死信转换。
- `outbox-idempotent-consumer`: 消费方按事件唯一 ID 判重、避免重复消息导致业务逻辑被重复执行的幂等处理能力。

### Modified Capabilities
(无 —— 不改动仓库中任何已有 spec 的既有需求)

## Impact

- **新增代码**:`src/main/java/com/example/template/outbox/**`(entity、mapper、event、listener、relay、publisher、consumer 等子包),`src/test/java/com/example/template/outbox/**` 的事务原子性与端到端测试。
- **新增配置**:在合适的 `@Configuration` 类(或应用启动类)上新增 `@EnableScheduling`。
- **新增 SQL 参考文件**:`src/main/resources/sql/outbox.sql`(`outbox_event`/`leave_request`/`processed_event` 建表语句,手动执行,不接入自动迁移)。
- **不影响**现有的 Web/OpenFeign/Redis/日志等既有基础设施配置;Outbox 相关表与仓库现有 `mybatis.plus.mapper` 包下的其它 Mapper 并列存在,复用同一套 MyBatis-Plus 全局配置,不新增额外的数据源或事务管理器。
- **文档**:`Outbox模式事务流程.md` 保留作为设计参考,不做修改。
