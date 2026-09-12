package com.example.template.approval.api.dto;

import java.time.LocalDateTime;

/**
 * 某个审批人的一条待办任务的对外视图。
 *
 * @param approvalId  稳定的审批实例标识
 * @param bizType     业务动作类型
 * @param bizId       业务单据标识
 * @param level       该任务所处的审批级别（从1开始）
 * @param taskTitle   任务标题
 * @param createdTime 任务创建时间
 */
public record ApprovalTaskView(Long approvalId, String bizType, String bizId, Integer level, String taskTitle,
                                LocalDateTime createdTime) {
}
