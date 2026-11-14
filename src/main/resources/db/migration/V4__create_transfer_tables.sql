-- 接口幂等示例(日常交易转账)用到的两张表：账户表 + 转账流水表。
-- 幂等的"最终保证"落在transfer_order.idempotency_key的唯一索引上：Redis幂等锁只负责把并发的重复请求
-- 挡在入口，锁一旦提前失效(租期到期/主从切换等)，仍然靠这个唯一索引保证同一幂等键只落一条流水、只扣一次款。

-- 账户表：余额用DECIMAL而不是浮点，避免金额计算出现精度误差
CREATE TABLE transfer_account (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    account_no    VARCHAR(32) NOT NULL,                 -- 账户号，对外标识，转账接口按它定位账户
    account_name  VARCHAR(64) NOT NULL,                 -- 户名
    balance       DECIMAL(18, 2) NOT NULL DEFAULT 0.00, -- 余额(元)，扣款用"balance >= 金额"的条件更新防止扣成负数
    create_time   DATETIME NOT NULL,
    update_time   DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_no (account_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 转账流水表：同时充当幂等记录表。"写流水"和"改余额"在同一个本地事务提交，
-- 所以不存在"记录了幂等键但钱没转"或"钱转了但没记录幂等键"的中间状态
CREATE TABLE transfer_order (
    id               BIGINT NOT NULL AUTO_INCREMENT,
    transfer_no      VARCHAR(32) NOT NULL,       -- 对外流水号，服务端生成的32位无横线UUID
    idempotency_key  VARCHAR(64) NOT NULL,       -- 调用方通过Idempotency-Key请求头传入的幂等键
    from_account_no  VARCHAR(32) NOT NULL,       -- 转出账户号
    to_account_no    VARCHAR(32) NOT NULL,       -- 转入账户号
    amount           DECIMAL(18, 2) NOT NULL,    -- 转账金额(元)
    status           VARCHAR(16) NOT NULL,       -- 目前只有SUCCESS：失败的转账整体回滚、不落流水
    remark           VARCHAR(255) NULL,          -- 备注，可空
    create_time      DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transfer_no (transfer_no),
    UNIQUE KEY uk_idempotency_key (idempotency_key)  -- 幂等判定的唯一依据，重复插入会触发唯一键冲突
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 预置两个演示账户，应用启动后可以直接在curl/Swagger里用它们试转账接口
INSERT INTO transfer_account (account_no, account_name, balance, create_time, update_time)
VALUES ('ACC1001', '演示账户-张三', 10000.00, NOW(), NOW()),
       ('ACC1002', '演示账户-李四', 5000.00, NOW(), NOW());
