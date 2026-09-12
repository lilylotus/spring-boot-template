package com.example.template.approval.api.dto;

/**
 * 审批实例状态。审批能力对外统一使用该枚举描述状态，业务方不感知底层工作流引擎的状态表达方式。
 */
public enum ApprovalStatus {

    /** 审批进行中。 */
    PENDING,

    /** 审批全部级别均已同意，整体通过。 */
    APPROVED,

    /** 审批在某一级被驳回，整体不通过。 */
    REJECTED
}
