-- 用户主表：新增/编辑动作的目标数据，不存任何 Flowable 相关列
CREATE TABLE sys_user
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username     VARCHAR(64)  NOT NULL COMMENT '账号，唯一',
    real_name    VARCHAR(64)  NOT NULL COMMENT '姓名',
    mobile       VARCHAR(32)  NULL COMMENT '手机号',
    email        VARCHAR(128) NULL COMMENT '邮箱',
    status       VARCHAR(16)  NOT NULL COMMENT '状态：PENDING-待审批 ACTIVE-已生效 REJECTED-已驳回',
    created_time DATETIME     NOT NULL COMMENT '创建时间',
    updated_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户主表';

-- 用户编辑场景下的“待生效变更”快照表：编辑发起审批时不直接修改 sys_user，
-- 而是在此表插入一条待审批记录，审批通过后再应用到 sys_user
CREATE TABLE user_pending_change
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '变更记录ID',
    user_id         BIGINT      NOT NULL COMMENT '目标用户ID（sys_user.id）',
    before_snapshot JSON        NOT NULL COMMENT '变更前的用户字段快照',
    after_snapshot  JSON        NOT NULL COMMENT '变更后的用户字段快照',
    status          VARCHAR(16) NOT NULL COMMENT '状态：PENDING-待审批 APPROVED-已通过 REJECTED-已驳回',
    operator_id     VARCHAR(64) NULL COMMENT '发起本次编辑的操作人标识',
    created_time    DATETIME    NOT NULL COMMENT '创建时间',
    updated_time    DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_user_pending_change_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户编辑待审批变更快照表';
