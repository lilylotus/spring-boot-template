-- 用户组审批：实例保存发布时的逻辑节点结构，动作记录保存所属逻辑节点ID。
-- 新增列均可空，历史实例与历史动作记录保持 NULL，由 approver_snapshot 与审批级别兜底。
ALTER TABLE approval_biz_link
    ADD COLUMN node_snapshot LONGTEXT NULL COMMENT '发起时逻辑节点结构 JSON 快照（节点ID、指派类型、用户ID或组ID，不含组成员）' AFTER approver_snapshot;

ALTER TABLE approval_action_record
    ADD COLUMN node_id VARCHAR(64) NULL COMMENT '处理时所属逻辑节点ID（画布节点ID）' AFTER task_id;
