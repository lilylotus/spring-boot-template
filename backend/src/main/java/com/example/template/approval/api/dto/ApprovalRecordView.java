package com.example.template.approval.api.dto;

import java.time.LocalDateTime;

/**
 * 当前审批人已完成的一条审批动作视图，不依赖具体工作流引擎类型。
 *
 * @param approvalId      稳定的审批实例标识
 * @param bizType         业务动作类型
 * @param bizId           业务单据标识
 * @param level           审批级别，从1开始
 * @param taskTitle       任务标题
 * @param action          同意或驳回动作
 * @param comment         审批意见，可为空
 * @param taskCreatedTime 任务到达时间
 * @param actedTime       任务处理时间
 */
public record ApprovalRecordView(
        Long approvalId,
        String bizType,
        String bizId,
        Integer level,
        String taskTitle,
        ApprovalAction action,
        String comment,
        LocalDateTime taskCreatedTime,
        LocalDateTime actedTime) {
}
