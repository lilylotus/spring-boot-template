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
 * @param nodeId          逻辑节点ID（画布节点ID）；历史记录为空
 * @param assigneeType    指派类型，USER 或 GROUP
 * @param groupId         处理时所属用户组ID；指定用户节点为空
 * @param groupName       处理时的用户组名称；指定用户节点为空
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
        LocalDateTime actedTime,
        String nodeId,
        String assigneeType,
        Long groupId,
        String groupName) {

    /** 兼容指定用户节点和旧历史记录：无逻辑节点及用户组信息，指派类型缺省为 USER。 */
    public ApprovalRecordView(Long approvalId, String bizType, String bizId, Integer level, String taskTitle,
                              ApprovalAction action, String comment, LocalDateTime taskCreatedTime,
                              LocalDateTime actedTime) {
        this(approvalId, bizType, bizId, level, taskTitle, action, comment, taskCreatedTime, actedTime,
                null, "USER", null, null);
    }
}
