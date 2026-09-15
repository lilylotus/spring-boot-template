## 1. 数据模型与建表 SQL

- [x] 1.1 编写 `src/main/resources/sql/outbox.sql`,包含 `outbox_event`、`leave_request`、`processed_event` 三张表的建表语句(参考仓库现有 `batch-test.sql` 的手动执行约定,不接入自动迁移)
- [x] 1.2 创建 `com.example.template.outbox` 及子包骨架:`entity`、`mapper`、`event`、`listener`、`relay`、`publisher`、`consumer`

## 2. Outbox 事件存储(outbox-event-store)

- [x] 2.1 定义 `OutboxEvent` 实体类(MyBatis-Plus `@TableName("outbox_event")`),字段含 id/aggregateId/eventType/payload/status/createTime/retryCount,补充类级注释
- [x] 2.2 定义状态枚举或常量(`PENDING`/`SENT`/`FAILED`)
- [x] 2.3 定义 `OutboxEventMapper extends BaseMapper<OutboxEvent>`,提供按状态+`create_time`升序+分批大小查询待处理记录的方法(`LambdaQueryWrapper` 即可,无需手写 XML)
- [x] 2.4 编写 `OutboxEventMapper` 的持久化单元测试:新建记录初始状态为 PENDING/retry_count 为 0;按状态分批查询返回数量与排序符合预期

## 3. 业务写入与 Outbox 写入同一事务(outbox-transactional-write)

- [x] 3.1 定义 `LeaveRequest` 实体(MyBatis-Plus,`@TableName("leave_request")`,含状态字段如 APPROVING/APPROVED/REJECTED)及对应 `LeaveRequestMapper`
- [x] 3.2 定义 `ApprovalFinishedEvent` 领域事件(含 businessKey/approved 等字段)
- [x] 3.3 实现 `ApprovalFinishedEventListener`:`@EventListener` + `@Transactional` 方法,先校验业务记录当前状态是否为 APPROVING(不满足则跳过),满足则更新业务表状态并写入一条 `OutboxEvent`(payload 用 Jackson 序列化该领域事件)
- [x] 3.4 编写事务原子性测试:正常路径验证业务表状态与 Outbox 记录同时提交;通过模拟(如 Mockito spy 让 Outbox 写入抛异常)验证业务表状态更新也被回滚,且不产生残留的 Outbox 记录
- [x] 3.5 编写"业务记录已不处于可更新状态"分支的测试,验证跳过处理、不产生重复更新或重复 Outbox 记录

## 4. 可插拔消息发布接口与消息中继(outbox-message-relay)

- [x] 4.1 定义 `OutboxMessagePublisher` 接口(按 eventType/payload 投递)
- [x] 4.2 定义 `OutboxMessageDeliveredEvent`(进程内事件,携带原始 Outbox 记录的关键信息)
- [x] 4.3 实现默认发布器 `InProcessOutboxMessagePublisher`,内部调用 `ApplicationEventPublisher.publishEvent()` 发布 `OutboxMessageDeliveredEvent`
- [x] 4.4 在合适的 `@Configuration` 类上新增 `@EnableScheduling`
- [x] 4.5 实现 `OutboxMessageRelay`:`@Scheduled(fixedDelay = 2000)` 定时任务,按批(默认 100 条)拉取 PENDING 记录,逐条调用 `OutboxMessagePublisher` 投递;成功标记 SENT,失败 `retryCount+1`,超过重试上限(默认 10)标记 FAILED;每条记录独立 `save`,互不影响
- [x] 4.6 编写中继任务单元测试:投递成功标记 SENT;投递失败且未超重试上限保持 PENDING 并递增 retryCount;投递失败且超过上限转 FAILED;模拟"进程重启"场景(直接用已有 PENDING 记录重新触发一次轮询)验证记录仍被正确处理

## 5. 消费方幂等处理(outbox-idempotent-consumer)

- [x] 5.1 定义 `ProcessedEvent` 实体(`@TableName("processed_event")`,以 event_id 为主键)及 `ProcessedEventMapper`
- [x] 5.2 实现示例消费者 `ApprovalEventConsumer`:监听 `OutboxMessageDeliveredEvent`,处理前用 `existsById` 判断 event_id 是否已处理,已处理则跳过;未处理则执行业务逻辑并插入 `ProcessedEvent` 记录
- [x] 5.3 编写幂等消费测试:首次收到事件正常处理并落地 processed_event 记录;重复收到同一事件(同一 event_id)第二次调用被跳过,不重复执行业务逻辑、不重复插入记录

## 6. 端到端验证与收尾

- [x] 6.1 编写端到端集成测试:发布 `ApprovalFinishedEvent` → 监听器同事务写入业务表与 Outbox → 手动触发一次中继轮询 → 验证 Outbox 记录变为 SENT 且下游消费者收到并处理 → 验证 `processed_event` 中存在对应记录
- [x] 6.2 运行 `./gradlew test --tests "com.example.template.outbox.*"` 及相关既有测试,确认新增代码与既有测试均通过(已在本地MySQL执行`sql/outbox.sql`建表，12个outbox测试全部通过)
- [x] 6.3 按仓库要求为每个新增 Java 类补充类级注释,为非平凡方法(事务写入、重试判断、幂等判重等核心逻辑)补充方法/行内注释,风格参考 `MybatisPlusConfig`/`TraceIdFilter`/`RedisConfig`
- [x] 6.4 自查代码风格(4 空格缩进、K&R 大括号、小驼峰命名、UTF-8 编码等)符合 `java-code-style` skill 规范
- [x] 6.5 确认本次改动未修改任何现有配置文件行为,`./gradlew build` 整体可通过(唯一5个失败测试是仓库既有的、与本次改动无关的infra依赖测试，与改动前基线一致，无新增回归)
