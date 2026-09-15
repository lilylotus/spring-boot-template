## Context

仓库是一个 Spring Boot 3.5 + Java 21 的"启动模板",已经具备 MySQL + MyBatis-Plus(`mybatis.plus.mapper`)、Jackson 序列化(`RedisConfig` 已在用)技术栈,但没有任何"本地事务保证消息不丢"的示例。`Outbox模式事务流程.md` 给出了完整流程:业务操作+写 Outbox(同一本地事务)→ 事务提交 → 消息中继轮询未发送记录 → 发布到 MQ/事件总线(成功标记已发送)→ 消费方幂等处理。本次改动把该流程落地为 `com.example.template.outbox` 包下的可运行代码,并用设计文档给出的请假审批例子做端到端演示。

## Goals / Non-Goals

**Goals:**
- 落地 Outbox 表结构、事务性写入、消息中继、可插拔发布接口、消费方幂等处理五个环节,端到端可运行并有测试覆盖。
- 用测试证明"业务更新与 Outbox 写入同一事务"这一核心保证:模拟 Outbox 写入失败时业务更新也会回滚。
- 消息中继的重试/死信状态完全持久化在表里,不依赖任何进程内存状态,可安全应对进程重启。
- 消费方按事件唯一 ID 判重,证明重复投递不会导致业务逻辑被重复执行。

**Non-Goals:**
- 不引入真实 MQ(Kafka/RabbitMQ 等)依赖或运维成本,发布接口只做到"可插拔、可替换"为止,不实现真实 MQ 生产者。
- 不实现 CDC(Debezium 等)方式的低延迟中继,只实现设计文档中"简单场景够用"的定时轮询方式。
- 不接入数据库自动迁移工具(Flyway/Liquibase),建表 SQL 按仓库现有 `batch-test.sql` 的方式提供参考脚本,手动执行。
- 不实现 Flowable/审批引擎集成本身,只借用设计文档中"请假审批"这个业务场景做 Outbox 模式的载体,不涉及真实审批流程引擎。
- 不做多实例部署下的中继任务分布式互斥(比如多个实例同时抢同一批 PENDING 记录导致重复投递)——设计文档给出的是单实例场景的基础流程,分布式场景需要额外加锁(可复用仓库已有的 [Redis 分布式锁服务](../../../src/main/java/com/example/template/redis/lock)),本次不在范围内,在风险中说明。

## Decisions

1. **Outbox/幂等表用 MyBatis-Plus `BaseMapper`,不新增手写 XML Mapper**
   仓库两套 MyBatis 栈并存(见 CLAUDE.md),`outbox_event`/`processed_event`/`leave_request` 都是简单的按主键/状态查询与更新,没有复杂 JOIN 或手写 SQL 优化的需求,用 MyBatis-Plus 的 `BaseMapper<T>` + `LambdaQueryWrapper` 即可满足,减少样板代码,与仓库里 `mybatis.plus.mapper` 包的既有用法保持一致。
   **替代方案**:用 `mybatis.mapper` 手写 XML——对这几张简单表来说是不必要的复杂度,不采用。

2. **业务写入放在 `@EventListener` + `@Transactional` 组合的监听器里,而不是直接内联在触发方法里**
   直接沿用设计文档给出的方案:`ApprovalFinishedEvent` 由触发审批完成的地方通过 `ApplicationEventPublisher.publishEvent()` 发出,真正"更新业务表 + 写 Outbox"的逻辑放在独立的 `@EventListener` 方法里,该方法自己开启 `@Transactional`。这样即使未来接入 Flowable 等外部引擎,只要引擎侧把"审批完成"这个事实通过 Spring 事件传递过来,业务侧监听器仍然能在自己的数据库事务里原子写入业务表和 Outbox 表——不依赖引擎自身的事务边界(设计文档第六节已指出这一点)。
   **替代方案**:让触发方法自己在一个大事务里做完业务更新和 Outbox 写入——对于当前这个自包含的演示场景两者等价,但采用事件监听器写法更贴合设计文档给出的、也更贴合未来接入外部引擎(审批引擎/工作流引擎)时的现实约束,因此按文档方式实现。

3. **消息发布接口默认实现用 `ApplicationEventPublisher`,不引入真实 MQ 依赖**
   `OutboxMessagePublisher` 接口只有一个方法(按 `eventType`/`payload` 发布);默认实现 `InProcessOutboxMessagePublisher` 把投递动作转换成一次 `ApplicationEventPublisher.publishEvent(new OutboxMessageDeliveredEvent(...))`,进程内的消费者监听这个事件即可完成整条链路的演示,不需要额外起 Kafka/RabbitMQ。
   **替代方案**:引入 Spring Cloud Stream 或直接依赖某个具体 MQ 客户端——增加了仓库依赖体积和本地开发/测试环境的基础设施要求(需要额外起 MQ 容器),与仓库当前"大多数测试要求 MySQL/Redis/Nacos 已经算重"的现状相悖,且真实 MQ 选型应该由使用这个模板的具体业务方决定,不应该在模板里预设。接口保持可插拔,业务方按需替换实现。

4. **中继任务轮询参数**
   按设计文档给出的默认值:`@Scheduled(fixedDelay = 2000)`(距离上次执行结束 2 秒后再次执行,避免任务执行时间超过间隔导致并发重入)、每批最多拉取 100 条 `PENDING` 记录、按 `create_time` 升序保证先入先出、单条记录失败重试上限 10 次后转 `FAILED`。这些是设计文档给出的默认调优参数,本次直接采用。

5. **重试计数与状态更新的原子性**
   中继任务处理每条记录后单独 `save`(更新 `status`/`retry_count`),不做整批事务提交——单条记录的发布与状态更新失败,不应该影响同一批次里其它记录的处理结果,这与设计文档"轮询任务失败了也没关系,outbox 记录还在表里,下一轮继续重试"的描述一致。

6. **消费方幂等表按事件唯一 ID(`outbox_event.id`)判重**
   `processed_event` 以 `event_id`(即对应 `outbox_event.id`)为主键,消费者处理前 `existsById` 判断,已存在则直接跳过;处理完成后插入该记录。`existsById` 检查与业务逻辑执行之间存在竞态窗口(并发消费同一事件)在本次范围内不做分布式锁处理,在风险中说明,设计文档本身给出的也是这个简化版本。

7. **`leave_request`/`outbox_event`/`processed_event` 建表 SQL 放在 `src/main/resources/sql/outbox.sql`**
   仓库没有集中的 schema 自动迁移机制(`batch-test.sql` 已经是"手动执行的参考脚本"这种用法的先例),延续这个约定,不引入 Flyway/Liquibase 等新工具。

## Risks / Trade-offs

- **[Risk] 中继任务在多实例部署下可能重复投递同一批 PENDING 记录** → 本次范围内只覆盖单实例场景;Mitigation:后续如需多实例部署,可复用仓库已有的 Redis 分布式锁(`com.example.template.redis.lock`)包一层"抢锁再轮询",不改变本次实现的表结构和核心逻辑。
- **[Risk] 消费方幂等判重与业务处理之间存在竞态窗口** → 理论上同一事件被并发消费两次时,两次 `existsById` 都可能返回 false。Mitigation:`processed_event.event_id` 设为主键,`INSERT` 阶段的主键唯一约束是最后一道防线,即使判重窗口被打穿,重复插入会因主键冲突失败,业务方可以在该异常发生时判定为"已被其它线程处理,跳过"(设计上留下这个约束,具体是否需要额外捕获处理在 tasks 中明确)。
- **[Risk] 用 `ApplicationEventPublisher` 模拟"发布到 MQ"不是真正跨进程的消息投递** → 只在同一个 JVM 进程内有效,不能验证真实网络分区/MQ 不可用场景下的行为。Mitigation:proposal 中已明确这是刻意的范围取舍(不引入 MQ 基础设施依赖),`OutboxMessagePublisher` 接口本身与真实 MQ 客户端解耦,替换实现即可获得真实的跨进程投递能力,不影响本次交付的中继任务/表结构/幂等逻辑的正确性验证。
- **[Trade-off] 不做 CDC 低延迟中继** → 轮询方式最坏情况下有 `fixedDelay`(2 秒)级别的投递延迟。Mitigation:设计文档本身说明"简单场景用定时轮询完全够用",生产如对延迟敏感可自行接入 Debezium 等 CDC 组件,不在本次范围。

## Migration Plan

全新模块,不影响现有功能,无需灰度发布策略。落地步骤:
1. 执行 `src/main/resources/sql/outbox.sql` 手动建表(`outbox_event`/`leave_request`/`processed_event`)。
2. 按 `outbox-event-store` → `outbox-transactional-write` → `outbox-message-relay` → `outbox-idempotent-consumer` 的依赖顺序实现(底层数据模型先行,业务写入依赖它,中继任务依赖数据模型,消费方依赖中继投递出来的事件)。
3. 新增 `@EnableScheduling` 配置,启用中继定时任务。
4. 编写测试验证事务原子性、中继轮询/重试/死信、消费方幂等三条主线。
5. 本次改动不修改任何现有配置文件/现有代码路径,可随时通过删除 `com.example.template.outbox` 包及对应 SQL/配置整体回滚,不影响仓库其它部分。

## Open Questions

- 消费方幂等的"主键冲突当作已处理跳过"这个兜底分支,是否需要在本次一并实现,还是先只实现基础的 `existsById` 判重、把并发竞态窗口作为已知限制记录在风险里、留到后续改动再补充? 倾向于本次先只实现基础判重(与设计文档给出的示例代码范围一致),把竞态加固作为后续可选增强,已在 Risks 中记录,如无异议将按此实现。
