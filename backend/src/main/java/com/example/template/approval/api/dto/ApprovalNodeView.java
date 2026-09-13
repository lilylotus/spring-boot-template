package com.example.template.approval.api.dto;

import java.time.LocalDateTime;

/**
 * 审批详情中的单个顺序审批节点。
 *
 * @param level           审批级别
 * @param approverUserId  审批人标识
 * @param status          节点状态
 * @param taskTitle       任务标题
 * @param action          已处理动作；未处理时为空
 * @param comment         审批意见
 * @param taskCreatedTime 任务到达时间
 * @param actedTime       任务处理时间
 */
public record ApprovalNodeView(
        Integer level,
        String approverUserId,
        ApprovalNodeStatus status,
        String taskTitle,
        ApprovalAction action,
        String comment,
        LocalDateTime taskCreatedTime,
        LocalDateTime actedTime,
        Long groupId,
        String groupName,
        java.util.List<String> currentMemberIds,
        java.util.List<ApprovalMemberActionView> memberActions) {

    /** 兼容个人节点和旧历史数据，无用户组附加信息。 */
    public ApprovalNodeView(Integer level, String approverUserId, ApprovalNodeStatus status, String taskTitle,
                            ApprovalAction action, String comment, LocalDateTime taskCreatedTime,
                            LocalDateTime actedTime) {
        this(level, approverUserId, status, taskTitle, action, comment, taskCreatedTime, actedTime,
                null, null, java.util.List.of(), java.util.List.of());
    }
}
