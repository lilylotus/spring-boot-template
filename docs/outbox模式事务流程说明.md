# Outbox 模式事务流程设计说明

## 1. 要解决的问题

在「更新数据库」和「发送 MQ 消息」这两个动作之间，天然存在**双写不一致**问题：

- 先更新 DB，再发 MQ：DB 提交成功后，如果进程崩溃 / 网络抖动导致发送失败，消息就永久丢失，下游感知不到这次业务变更。
- 先发 MQ，再更新 DB：消息发出去了，但本地事务回滚或写库失败，下游会处理一个"从未真正发生"的业务事件。
- 用分布式事务（如 XA / Seata）：强一致，但性能差、侵入性强、对 MQ 的支持也参差不齐，绝大多数场景没必要。

Outbox（发件箱）模式的思路是：**把"业务操作"和"要发出去的消息"绑定在同一个本地事务里落库**，消息本身的对外投递则异步、独立地完成，用「本地事务的原子性」替代「跨系统的分布式事务」。

## 2. 核心设计：两张表 + 一次本地事务

| 表 | 作用 |
|---|---|
| 业务表（如 `orders`） | 保存业务状态本身 |
| Outbox 表（如 `outbox_message`） | 保存"待发布的事件"，本质是一个可靠消息队列的落地存储 |

两张表必须在**同一个数据库**里，这样才能利用数据库自身的本地事务保证：业务数据的变更和事件记录的写入，要么同时成功，要么同时失败，不存在"改了库但没记事件"或"记了事件但库没改"的中间态。

## 3. 完整事务流程

### 阶段一：同步部分（业务线程内，强一致）

```
┌─────────────────────────────────────────┐
│           本地数据库事务 BEGIN             │
│                                           │
│  1. 执行业务逻辑，更新/插入业务表           │
│     （如：订单状态 CREATED -> PAID）        │
│                                           │
│  2. 在同一事务内，向 outbox 表插入一条记录   │
│     - message_id（全局唯一，供下游幂等）     │
│     - aggregate_type / aggregate_id       │
│     - event_type（如 OrderPaid）           │
│     - topic / tag（投递目标）               │
│     - payload（事件内容，JSON）             │
│     - status = PENDING                    │
│                                           │
│           本地数据库事务 COMMIT            │
└─────────────────────────────────────────┘
```

这一步完全在业务方法内部同步完成，`@Transactional` 保证原子性。此时消息**还没有真正发到 MQ**，只是"确定了这条消息一定要发"这件事被可靠地记录了下来。到这一步，事务提交成功 = 业务变更和"要发消息"的意图，已经一起落地，不会丢。

### 阶段二：异步部分（独立线程/进程，最终一致）

有两种主流实现方式：

**方式 A：轮询发布（Polling Publisher）**

```
定时任务（如每 1s 一次）
  │
  ├─ 1. 查询 outbox 表中 status = PENDING 的记录（按 created_at 升序，限量取一批）
  │
  ├─ 2. 逐条发送到 RocketMQ
  │     - 用 message_id 作为消息的 KEYS，供消费端去重
  │
  ├─ 3a. 发送成功 → 更新该记录 status = SENT, sent_at = now()
  │
  └─ 3b. 发送失败 → retry_count += 1
         - 未超过最大重试次数：保留 PENDING，等待下次轮询重试（可配合退避策略 next_retry_at）
         - 超过最大重试次数：status = FAILED，转人工介入 / 告警
```

实现简单，对现有系统侵入小，是本项目采用的方式；缺点是有轮询间隔带来的延迟，且轮询本身有一定数据库压力。

**方式 B：CDC 订阅 Binlog（如 Debezium）**

```
业务事务提交（写入 outbox 表）
  │
  └─ MySQL binlog 产生一条 INSERT 记录
        │
        └─ Debezium 监听 binlog，捕获这条变更
              │
              └─ 直接转换并投递到 RocketMQ（无需业务方写发布代码）
```

不侵入业务库的读写路径，实时性更好（毫秒级），但引入了额外的中间件（Debezium + Kafka Connect 等），运维成本更高，通常在消息量大、对延迟敏感的场景才引入。

## 4. 消费端：为什么必须幂等

Outbox 模式提供的是 **at-least-once（至少一次）** 投递语义，而不是 exactly-once：

- 轮询发布器可能"发送成功但更新 status 时进程崩溃"，导致下次轮询重复发送同一条消息；
- MQ Broker 与 Consumer 之间的网络问题也可能导致消息重投。

因此消费端必须依赖 `message_id`（即消息 KEYS）做幂等处理，常见做法：

- 消费端维护一张"已处理消息表"，处理前先插入 `message_id`（唯一索引），插入冲突则说明已处理过，直接跳过；
- 或利用业务本身的天然幂等性（如按主键 upsert、状态机只允许单向流转）。

## 5. 状态机小结

```
outbox_message.status:

PENDING ──发送成功──▶ SENT

PENDING ──发送失败，retry_count++──▶ PENDING（等待重试）
                                        │
                                重试次数耗尽
                                        ▼
                                     FAILED（告警/人工介入）
```

## 6. 小结：为什么这样设计是可靠的

1. **原子性**：业务变更与"待发布事件"的记录，靠数据库本地事务保证同时成功或同时失败，不存在只改库不记事件的情况。
2. **不丢**：只要本地事务提交成功，事件就已经落盘，即使发布进程崩溃，重启后轮询仍能捡起 PENDING 记录继续发。
3. **可重试**：失败有 retry_count 和 FAILED 兜底，不会无限重试拖垮系统，也不会静默丢失。
4. **最终一致**：牺牲了强一致和实时性（毫秒到秒级延迟），换来不依赖分布式事务的简单可靠架构，符合绝大多数业务场景对一致性的实际要求。

## 7. 表设计

`schema.sql`

```sql
-- ========================================================
-- Outbox 模式示例建表脚本
-- 业务表 orders + 事件表 outbox_message 必须放在同一个数据库、
-- 同一个事务里写入，这是 Outbox 模式成立的前提。
-- ========================================================

CREATE DATABASE IF NOT EXISTS outbox_demo DEFAULT CHARACTER SET utf8mb4;
USE outbox_demo;

-- 业务表：订单
CREATE TABLE IF NOT EXISTS `orders` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `order_no`   VARCHAR(64)  NOT NULL COMMENT '订单号',
    `amount`     DECIMAL(18,2) NOT NULL COMMENT '订单金额',
    `status`     VARCHAR(32)  NOT NULL COMMENT '订单状态：CREATED / PAID / CANCELLED',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- Outbox 表：待发布的领域事件
-- 必须和业务表同库，才能利用同一个本地事务保证原子性
CREATE TABLE IF NOT EXISTS `outbox_message` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `message_id`    VARCHAR(64)  NOT NULL COMMENT '全局唯一消息ID，供消费端幂等去重',
    `aggregate_type` VARCHAR(64) NOT NULL COMMENT '聚合根类型，如 Order',
    `aggregate_id`  VARCHAR(64)  NOT NULL COMMENT '聚合根ID，如订单号',
    `event_type`    VARCHAR(64)  NOT NULL COMMENT '事件类型，如 OrderPaid',
    `topic`         VARCHAR(64)  NOT NULL COMMENT '目标 RocketMQ Topic',
    `tag`           VARCHAR(64)  DEFAULT NULL COMMENT '目标 RocketMQ Tag',
    `payload`       TEXT         NOT NULL COMMENT '消息体，JSON 格式',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / FAILED',
    `retry_count`   INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `next_retry_at` DATETIME     DEFAULT NULL COMMENT '下次可重试时间，用于失败退避',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `sent_at`       DATETIME     DEFAULT NULL COMMENT '成功发送时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_id` (`message_id`),
    KEY `idx_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Outbox 事件表';

```

`outbox-demo.zip` 完整的 Spring Boot + MySQL + RocketMQ Outbox 模式项目，结构如下：

- **entity**: `Order`（业务表）、`OutboxMessage`（事件表，含状态机 PENDING/SENT/FAILED）
- **repository**: `OrderRepository`、`OutboxRepository`（按状态+创建时间分页查询待发送消息）
- **service**: `OrderService` —— 核心演示，`createOrder`/`payOrder` 在**同一个 `@Transactional`** 方法里同时写业务表和 outbox 表
- **scheduler**: `OutboxPublisher` —— 定时轮询 PENDING 消息，用 `message_id` 作为 RocketMQ 的 KEYS 发送，成功标记 SENT，失败计数重试直至 FAILED
- **mq**: `OutboxEventPayload`（消息体）、`OrderEventConsumer`（消费端幂等示例，按 messageId 去重）
- **controller**: `OrderController` —— `POST /api/orders` 建单、`POST /api/orders/{orderNo}/pay` 支付，方便本地联调
- **config**: `OutboxPublisherProperties` —— 绑定 `application.yml` 里的轮询参数
- `schema.sql` / `application.yml` / `pom.xml` 都已配好
