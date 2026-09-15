# Outbox模式事务流程

## 一、问题的起点

如果直接这么写：

```java
@Transactional
public void onApprovalFinished(ApprovalFinishedEvent event) {
    updateBusinessStatus(event);      // 写数据库，事务A
    messageQueue.send(event);         // 发MQ，是另一个独立的网络调用
}
```

会有两种典型故障：
- 数据库更新成功了，但发MQ那一刻网络抖动/进程被杀，消息永远发不出去——业务状态改了，但下游系统（通知、报表、其他系统）永远不知道。
- 反过来，如果先发MQ再写库，消息发出去了但数据库事务回滚，下游收到了一个"根本没发生过"的事件。

Outbox模式的思路是：**把"发消息"这个动作，本身也变成一次数据库写入**，这样它就能跟业务更新一起被同一个本地事务原子保护，彻底把"跨系统的可靠性问题"降级成"单库事务的原子性问题"（这个数据库天然就能保证）。

整体流程：

```
业务操作+写入Outbox记录（同一本地事务内完成）
        ↓
事务提交（业务数据与Outbox记录同生共死）
        ↓
消息中继(轮询/CDC)（扫描未发送的Outbox记录）
        ↓
发布到MQ/事件总线（投递成功后标记已发送）
        ↓
消费方处理（幂等消费+业务状态回写）
```
如果投递失败，消息中继会对未标记成功的记录重试（对未发送记录的重试回路）。

---

## 二、第一步：Outbox表结构

```sql
CREATE TABLE outbox_event (
    id            VARCHAR(36) PRIMARY KEY,
    aggregate_id  VARCHAR(64) NOT NULL,   -- 对应businessKey，比如leaveRequestId
    event_type    VARCHAR(64) NOT NULL,   -- ApprovalFinishedEvent
    payload       TEXT NOT NULL,          -- 事件内容序列化成JSON
    status        VARCHAR(16) NOT NULL,   -- PENDING / SENT / FAILED
    create_time   TIMESTAMP NOT NULL,
    retry_count   INT DEFAULT 0
);
```

---

## 三、第二步：业务写入和Outbox写入在同一事务

```java
@EventListener
@Transactional
public void onApprovalFinished(ApprovalFinishedEvent event) {
    LeaveRequest request = repository.findById(event.businessKey()).orElseThrow();
    if ("APPROVING".equals(request.getStatus())) {
        request.setStatus(event.approved() ? "APPROVED" : "REJECTED");
        repository.save(request);                       // 写业务表
        outboxRepository.save(OutboxEvent.of(event));    // 写outbox表，同一个@Transactional
    }
}
```

这两行`save`在同一个事务里，数据库保证要么都提交、要么都回滚，**不存在"业务状态改了但outbox记录没写上"这种中间态**，这一步是整个模式的地基。

---

## 四、第三步：消息中继（Message Relay）——真正对外发送的地方

Outbox表此时只是"待发送的草稿箱"，还需要一个独立的进程/定时任务把它真正投递出去，这一步和业务事务彻底解耦，允许失败重试：

```java
@Scheduled(fixedDelay = 2000)
public void relay() {
    List<OutboxEvent> pending = outboxRepository.findByStatusOrderByCreateTime("PENDING", 100);
    for (OutboxEvent e : pending) {
        try {
            messageQueue.send(e.getEventType(), e.getPayload());
            e.setStatus("SENT");
        } catch (Exception ex) {
            e.setRetryCount(e.getRetryCount() + 1);
            if (e.getRetryCount() > 10) e.setStatus("FAILED"); // 进死信，人工介入
        }
        outboxRepository.save(e);
    }
}
```

这个轮询任务失败了也没关系——outbox记录还在表里，状态还是`PENDING`，下一轮继续重试，**不会丢**。

生产环境如果对延迟要求更高，轮询方式（几秒一次）可以换成**CDC（Change Data Capture，比如Debezium监听Outbox表的binlog）**，一有新记录写入立刻触发投递，延迟能做到毫秒级，代价是多引入一套CDC组件的运维成本，简单场景用定时轮询完全够用。

---

## 五、第四步：消费方幂等处理

消费方拿到消息后的处理逻辑，必须假设**同一条消息可能会被投递不止一次**（比如中继重试导致重复发送，或者MQ本身的at-least-once语义），处理逻辑要幂等：

```java
@KafkaListener(topics = "approval-events")
public void handle(String payload) {
    ApprovalFinishedEvent event = deserialize(payload);
    // 用事件里带的唯一ID做幂等判断，比如查一张processed_event表
    if (processedEventRepository.existsById(event.eventId())) return;
    doBusinessLogic(event);
    processedEventRepository.save(new ProcessedEvent(event.eventId()));
}
```

---

## 六、和其他相关设计的联系

- 数据同步中"change_log全局seq + 应用自己的游标"是同一个模式的两种实现：change_log本质上就是一张"全局共享的outbox"，应用拉取相当于消费者主动来轮询，而这里的Outbox是"一对一/一对多推送"的写法，核心思路都是"先落地一条不会丢的记录，再异步、可重试地对外通知"。
- 跟Flowable审批场景结合时，写Outbox这一步最好放在**业务侧的事件监听器**里而不是Flowable自己的`ExecutionListener`里——因为`ExecutionListener`执行在Flowable引擎自己的事务里，如果Flowable用的是独立数据库/独立schema，它没法跟业务库的Outbox表在同一个事务里原子提交；让Spring事件传到业务侧、业务侧自己的事务里去写Outbox，才能保证"业务状态更新"和"Outbox记录"这两者的原子性。
