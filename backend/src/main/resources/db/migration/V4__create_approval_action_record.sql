-- 产品侧审批动作审计记录：独立于 Flowable 历史表稳定保留逐级审批结果
CREATE TABLE approval_action_record
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_type             VARCHAR(32)   NOT NULL COMMENT '业务动作类型',
    biz_id               VARCHAR(64)   NOT NULL COMMENT '业务单据标识',
    process_instance_id  VARCHAR(64)   NOT NULL COMMENT 'Flowable 流程实例ID',
    task_id               VARCHAR(64)   NOT NULL COMMENT 'Flowable 任务ID',
    approver_user_id     VARCHAR(64)   NOT NULL COMMENT '审批人标识',
    level_no             INT           NOT NULL COMMENT '审批级别，从1开始',
    task_title           VARCHAR(255)  NULL COMMENT '任务标题',
    action               VARCHAR(16)   NOT NULL COMMENT '审批动作：AGREE-同意 REJECT-驳回',
    comment              VARCHAR(1000) NULL COMMENT '审批意见',
    task_created_time    DATETIME      NULL COMMENT '任务到达时间',
    acted_time           DATETIME      NOT NULL COMMENT '处理时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_action_record_task_id (task_id),
    KEY idx_approval_action_record_approver_time (approver_user_id, acted_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='审批动作审计记录';
