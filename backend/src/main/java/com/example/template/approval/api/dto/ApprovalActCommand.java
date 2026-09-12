package com.example.template.approval.api.dto;

/**
 * 审批人对当前审批任务执行同意/驳回操作的命令。
 *
 * @param bizType        业务动作类型
 * @param bizId          业务单据标识
 * @param approverUserId 执行本次操作的审批人标识
 * @param action         同意或驳回
 * @param comment        审批意见（可为空）
 */
public record ApprovalActCommand(String bizType, String bizId, String approverUserId, ApprovalAction action,
                                  String comment) {
}
