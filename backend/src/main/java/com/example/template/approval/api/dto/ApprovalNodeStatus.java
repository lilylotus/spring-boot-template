package com.example.template.approval.api.dto;

/**
 * 审批详情中单个审批节点的状态。
 */
public enum ApprovalNodeStatus {

    /** 节点已同意。 */
    APPROVED,

    /** 节点已驳回。 */
    REJECTED,

    /** 节点正在等待当前审批人处理。 */
    PENDING,

    /** 节点尚未开始。 */
    WAITING,

    /** 节点因前序驳回而跳过。 */
    SKIPPED
}
