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
        LocalDateTime actedTime) {
}
