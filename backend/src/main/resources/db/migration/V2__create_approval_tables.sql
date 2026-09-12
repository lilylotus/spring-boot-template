-- 审批全局开关：单行总开关，不区分新增/编辑
CREATE TABLE approval_switch
(
    id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键，固定使用单行记录',
    approval_enabled TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '全局审批开关：0-关闭 1-开启',
    updated_time     DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='审批全局开关（单行总开关）';

INSERT INTO approval_switch (id, approval_enabled, updated_time)
VALUES (1, 0, NOW());

-- 指定用户多级审批链配置：按 biz_type 分别维护，级别从 1 开始连续
CREATE TABLE approval_chain_config
(
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_type          VARCHAR(32) NOT NULL COMMENT '业务动作类型，如 USER_CREATE / USER_EDIT',
    level_no          INT         NOT NULL COMMENT '审批级别，从1开始连续',
    approver_user_id  VARCHAR(64) NOT NULL COMMENT '该级别指定的审批人',
    created_time      DATETIME    NOT NULL COMMENT '创建时间',
    updated_time      DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_chain_biztype_level (biz_type, level_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='指定用户多级审批链配置';

-- 业务单据 <-> Flowable 流程实例 的关联表：业务表不直接持有 Flowable 运行时ID，
-- 一律通过本表间接关联，业务查询审批状态经 ApprovalGateway.queryByBizKey 内部完成映射
CREATE TABLE approval_biz_link
(
    id                   BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_type             VARCHAR(32) NOT NULL COMMENT '业务动作类型',
    biz_id               VARCHAR(64) NOT NULL COMMENT '业务单据标识',
    process_instance_id  VARCHAR(64) NOT NULL COMMENT 'Flowable 流程实例ID',
    status               VARCHAR(16) NOT NULL COMMENT '状态：PENDING-进行中 APPROVED-已通过 REJECTED-已驳回',
    created_time         DATETIME    NOT NULL COMMENT '创建时间',
    updated_time         DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_approval_biz_link_biztype_bizid (biz_type, biz_id),
    UNIQUE KEY uk_approval_biz_link_process_instance (process_instance_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='业务单据与流程实例关联表';
