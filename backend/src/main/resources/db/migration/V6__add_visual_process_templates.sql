CREATE TABLE approval_process_template
(
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '逻辑模板主键',
    biz_type          VARCHAR(64)  NOT NULL COMMENT '业务类型',
    scope_key         VARCHAR(64)  NOT NULL DEFAULT 'GLOBAL' COMMENT '适用范围，首版固定 GLOBAL',
    name              VARCHAR(128) NOT NULL COMMENT '模板名称',
    draft_model_json  LONGTEXT     NOT NULL COMMENT '当前画布草稿 JSON',
    draft_revision    BIGINT       NOT NULL DEFAULT 1 COMMENT '草稿乐观锁版本',
    active_version_id BIGINT       NULL COMMENT '当前生效模板版本主键',
    created_time      DATETIME     NOT NULL,
    updated_time      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_process_template_biz_scope (biz_type, scope_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='审批流程逻辑模板与业务映射';

CREATE TABLE approval_process_template_version
(
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '模板版本主键',
    template_id            BIGINT       NOT NULL COMMENT '逻辑模板主键',
    version_no             INT          NOT NULL COMMENT '业务模板版本号',
    model_json             LONGTEXT     NOT NULL COMMENT '发布时画布模型快照',
    bpmn_xml               LONGTEXT     NOT NULL COMMENT '发布生成的 BPMN XML',
    deployment_id          VARCHAR(64)  NOT NULL COMMENT 'Flowable 部署 ID',
    process_definition_id  VARCHAR(128) NOT NULL COMMENT 'Flowable 流程定义 ID',
    process_definition_key VARCHAR(128) NOT NULL COMMENT 'Flowable 流程定义 key',
    published_time         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_template_version (template_id, version_no),
    KEY idx_template_version_definition (process_definition_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='审批流程不可变发布版本';

ALTER TABLE approval_biz_link
    ADD COLUMN business_key VARCHAR(160) NULL COMMENT 'Flowable businessKey' AFTER process_instance_id,
    ADD COLUMN template_id BIGINT NULL COMMENT '逻辑模板主键' AFTER business_key,
    ADD COLUMN template_name VARCHAR(128) NULL COMMENT '发起时模板名称' AFTER template_id,
    ADD COLUMN template_version_id BIGINT NULL COMMENT '模板版本主键' AFTER template_name,
    ADD COLUMN template_version_no INT NULL COMMENT '模板版本号' AFTER template_version_id,
    ADD COLUMN process_definition_id VARCHAR(128) NULL COMMENT 'Flowable 流程定义 ID' AFTER template_version_no;
