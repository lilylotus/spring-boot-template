# outbox-idempotent-consumer Specification

## Purpose

消费方按事件唯一 ID 判重、避免重复消息导致业务逻辑被重复执行的幂等处理能力,演示同一条消息被重复投递也不会重复执行业务逻辑。

## Requirements

### Requirement: 消费方按事件唯一 ID 判重
系统 SHALL 提供一个消费者示例,在处理消息前先按事件唯一 ID 查询 `processed_event` 表判断该事件是否已被处理过;已处理过的事件 SHALL 被跳过,不重复执行业务逻辑。

#### Scenario: 首次处理某事件
- **WHEN** 消费者收到一个 `processed_event` 表中不存在对应记录的事件
- **THEN** 系统 SHALL 执行该事件对应的业务处理逻辑,并在处理完成后向 `processed_event` 表插入一条以该事件 ID 为标识的记录

#### Scenario: 重复收到同一事件
- **WHEN** 消费者收到一个 `processed_event` 表中已存在对应记录的事件(例如中继任务重试导致重复投递)
- **THEN** 系统 SHALL 跳过该事件的业务处理逻辑,SHALL NOT 重复执行,也 SHALL NOT 重复插入 `processed_event` 记录
