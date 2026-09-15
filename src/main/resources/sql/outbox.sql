-- Outbox模式相关表结构，手动执行建表，不接入自动迁移(仓库当前没有Flyway/Liquibase等工具，
-- 延续batch-test.sql"手动执行的参考脚本"这种用法约定)。
-- 对应文档: Outbox模式事务流程.md

-- outbox事件表：业务写入和outbox写入在同一本地事务提交，消息中继任务轮询PENDING记录对外投递
CREATE TABLE outbox_event (
    id            VARCHAR(36) NOT NULL,       -- UUID，应用侧生成(IdType.INPUT)，不用自增id
    aggregate_id  VARCHAR(64) NOT NULL,       -- 业务聚合标识，对应leave_request.id
    event_type    VARCHAR(64) NOT NULL,       -- 事件类型，如ApprovalFinishedEvent
    payload       TEXT NOT NULL,              -- 事件内容，Jackson序列化后的JSON文本
    status        VARCHAR(16) NOT NULL,       -- PENDING / SENT / FAILED
    create_time   DATETIME NOT NULL,
    retry_count   INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_status_create_time (status, create_time)  -- 消息中继按status过滤+create_time排序分批查询
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 请假审批业务表，作为Outbox模式"业务写入与Outbox写入同一事务"的演示载体
CREATE TABLE leave_request (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    status        VARCHAR(16) NOT NULL,       -- APPROVING / APPROVED / REJECTED
    create_time   DATETIME NOT NULL,
    update_time   DATETIME NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 消费方幂等记录表：以event_id(对应outbox_event.id)为主键，处理前先查是否已存在
CREATE TABLE processed_event (
    event_id      VARCHAR(36) NOT NULL,
    process_time  DATETIME NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
