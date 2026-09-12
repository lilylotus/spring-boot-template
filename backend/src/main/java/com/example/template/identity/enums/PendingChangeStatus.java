package com.example.template.identity.enums;

/**
 * 用户编辑“待生效变更”记录的状态。
 */
public enum PendingChangeStatus {

    /** 待审批。 */
    PENDING,

    /** 审批通过，变更已应用到用户已生效信息。 */
    APPROVED,

    /** 审批驳回，变更内容被丢弃。 */
    REJECTED
}
