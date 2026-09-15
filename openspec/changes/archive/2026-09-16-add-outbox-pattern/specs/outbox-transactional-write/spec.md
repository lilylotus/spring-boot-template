## ADDED Requirements

### Requirement: 业务更新与 Outbox 写入同一本地事务
系统 SHALL 在处理领域事件(如请假审批完成事件)时,把"更新业务表状态"和"写入 Outbox 记录"这两步纳入同一个 `@Transactional` 方法内执行,由数据库保证两者要么同时提交、要么同时回滚。

#### Scenario: 业务更新与 Outbox 写入同时成功
- **WHEN** 请假审批完成事件被监听器处理,业务表更新和 Outbox 记录写入均未发生异常
- **THEN** 事务提交后,业务表中对应记录的状态已更新,且 `outbox_event` 表中存在一条与该事件对应、状态为 `PENDING` 的新记录

#### Scenario: Outbox 写入失败时业务更新一并回滚
- **WHEN** 请假审批完成事件被监听器处理,业务表更新成功执行,但随后写入 Outbox 记录时发生异常
- **THEN** 系统 SHALL 回滚整个事务,业务表中对应记录的状态 SHALL NOT 被更新,`outbox_event` 表中 SHALL NOT 出现对应记录,不存在"业务状态已改但 Outbox 记录缺失"的中间态

### Requirement: 仅在业务状态满足前置条件时才处理
系统 SHALL 在写入前校验业务记录当前状态是否处于允许被该事件更新的状态(如仅当请假记录处于"审批中"状态时才处理审批完成事件),不满足条件时不执行更新也不写入 Outbox。

#### Scenario: 业务记录已不处于可更新状态
- **WHEN** 请假审批完成事件到达时,对应业务记录的当前状态已经不是"审批中"(例如已被处理过)
- **THEN** 系统 SHALL 跳过本次业务更新与 Outbox 写入,不产生重复的状态变更或重复的 Outbox 记录
