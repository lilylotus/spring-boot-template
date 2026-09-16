-- 把sql/outbox.sql中的建表语句迁移到Flyway管理。这三张表在部分环境（如当前联调用的共享测试库）
-- 是之前手动执行outbox.sql已经建好的，所以用CREATE TABLE IF NOT EXISTS，
-- 保证在"表已存在"和"全新环境表不存在"两种情况下都能正常执行，不阻断应用启动。

-- outbox事件表：业务写入和outbox写入在同一本地事务提交，消息中继任务轮询PENDING记录对外投递
CREATE TABLE IF NOT EXISTS outbox_event (
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
CREATE TABLE IF NOT EXISTS leave_request (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    status        VARCHAR(16) NOT NULL,       -- APPROVING / APPROVED / REJECTED
    create_time   DATETIME NOT NULL,
    update_time   DATETIME NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 消费方幂等记录表：以event_id(对应outbox_event.id)为主键，处理前先查是否已存在
CREATE TABLE IF NOT EXISTS processed_event (
    event_id      VARCHAR(36) NOT NULL,
    process_time  DATETIME NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
