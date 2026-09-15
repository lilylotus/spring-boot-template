# outbox-event-store Specification

## Purpose

Outbox 事件表结构、实体、状态流转(PENDING/SENT/FAILED)与基础的持久化读写能力,为事务性写入与消息中继提供统一的数据结构与查询/更新原语。

## Requirements

### Requirement: Outbox 事件表结构
系统 SHALL 提供 `outbox_event` 表(及对应实体),字段包括:`id`(主键)、`aggregate_id`(业务聚合标识,如 `leaveRequestId`)、`event_type`(事件类型)、`payload`(事件内容的 JSON 序列化文本)、`status`(`PENDING`/`SENT`/`FAILED`)、`create_time`、`retry_count`。

#### Scenario: 新建 Outbox 记录初始状态为 PENDING
- **WHEN** 系统构造一条新的 Outbox 记录并保存
- **THEN** 保存后的记录 `status` 字段值为 `PENDING`,`retry_count` 初始为 0

### Requirement: 按状态分批查询待处理记录
系统 SHALL 提供按 `status` 过滤、按 `create_time` 升序排序、支持分批大小限制的查询能力,用于消息中继任务获取待处理记录。

#### Scenario: 查询指定数量的待发送记录
- **WHEN** 调用方按 `status = PENDING` 且限制返回数量为 N 发起查询,且待处理记录数超过 N
- **THEN** 系统 SHALL 返回按 `create_time` 从早到晚排序的前 N 条记录,不多不少

### Requirement: 记录状态与重试次数更新
系统 SHALL 支持将某条 Outbox 记录的 `status` 更新为 `SENT` 或 `FAILED`,以及对 `retry_count` 递增,且更新对单条记录独立生效,不影响同批次其它记录。

#### Scenario: 单条记录更新失败不影响其它记录
- **WHEN** 同一批待处理记录中,某一条记录的状态更新操作发生异常
- **THEN** 系统 SHALL 保证其余记录的状态更新不受影响,各自独立提交
