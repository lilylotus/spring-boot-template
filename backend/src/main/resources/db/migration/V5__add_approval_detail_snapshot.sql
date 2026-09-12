ALTER TABLE approval_biz_link
    ADD COLUMN initiator_user_id VARCHAR(64) NULL COMMENT '发起人标识' AFTER status,
    ADD COLUMN approver_snapshot LONGTEXT NULL COMMENT '发起时审批人链 JSON 快照' AFTER initiator_user_id,
    ADD COLUMN payload_snapshot LONGTEXT NULL COMMENT '发起时业务数据 JSON 快照' AFTER approver_snapshot;
