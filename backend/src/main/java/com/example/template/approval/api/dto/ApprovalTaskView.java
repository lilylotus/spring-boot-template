package com.example.template.approval.api.dto;

import java.time.LocalDateTime;

/**
 * 某个审批人的一条待办任务的对外视图。
 *
 * @param approvalId   稳定的审批实例标识
 * @param bizType      业务动作类型
 * @param bizId        业务单据标识
 * @param level        该任务所处的审批级别（从1开始）
 * @param taskTitle    任务标题
 * @param createdTime  任务创建时间
 * @param groupId      用户组节点的用户组ID；指定用户节点为空
 * @param groupName    用户组节点的当前组名称；指定用户节点为空
 * @param confirmOnly  是否仅需确认全员驳回结果
 * @param nodeId       逻辑节点ID（画布节点ID）；无节点快照的历史实例为空
 * @param assigneeType 指派类型，USER 或 GROUP
 */
public record ApprovalTaskView(Long approvalId, String bizType, String bizId, Integer level, String taskTitle,
                                LocalDateTime createdTime, Long groupId, String groupName, boolean confirmOnly,
                                String nodeId, String assigneeType) {
    /** 兼容现有指定用户待办，无组信息且不需要结算确认。 */
    public ApprovalTaskView(Long approvalId, String bizType, String bizId, Integer level, String taskTitle,
                            LocalDateTime createdTime) {
        this(approvalId, bizType, bizId, level, taskTitle, createdTime, null, null, false);
    }

    /** 兼容不提供逻辑节点信息的调用方：节点ID为空，指派类型按是否有组信息推断。 */
    public ApprovalTaskView(Long approvalId, String bizType, String bizId, Integer level, String taskTitle,
                            LocalDateTime createdTime, Long groupId, String groupName, boolean confirmOnly) {
        this(approvalId, bizType, bizId, level, taskTitle, createdTime, groupId, groupName, confirmOnly,
                null, groupId == null ? "USER" : "GROUP");
    }
}
