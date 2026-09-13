CREATE TABLE sys_user_group (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户组ID',
    code VARCHAR(64) NOT NULL COMMENT '唯一编码',
    name VARCHAR(128) NOT NULL COMMENT '名称',
    description VARCHAR(500) NULL COMMENT '说明',
    status VARCHAR(16) NOT NULL COMMENT 'ACTIVE或DISABLED',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '编辑版本',
    created_by VARCHAR(64) NOT NULL COMMENT '创建人',
    created_time DATETIME NOT NULL COMMENT '创建时间',
    updated_by VARCHAR(64) NOT NULL COMMENT '更新人',
    updated_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_group_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户组';

CREATE TABLE sys_user_group_member (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '成员关系ID',
    group_id BIGINT NOT NULL COMMENT '用户组ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    created_by VARCHAR(64) NOT NULL COMMENT '创建人',
    created_time DATETIME NOT NULL COMMENT '创建时间',
    updated_by VARCHAR(64) NOT NULL COMMENT '更新人',
    updated_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_member (group_id, user_id),
    KEY idx_group_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户组成员';

ALTER TABLE approval_action_record
    DROP INDEX uk_approval_action_record_task_id,
    ADD UNIQUE KEY uk_approval_action_task_user (task_id, approver_user_id),
    ADD COLUMN group_id BIGINT NULL COMMENT '处理时所属用户组',
    ADD COLUMN group_name VARCHAR(128) NULL COMMENT '处理时用户组名称';
