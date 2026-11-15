package com.example.template.approval.api.dto;

import java.time.LocalDateTime;

/**
 * 审批详情中的单个顺序审批节点。
 *
 * @param level            审批级别
 * @param approverUserId   审批人标识
 * @param status           节点状态
 * @param taskTitle        任务标题
 * @param action           已处理动作；未处理时为空
 * @param comment          审批意见
 * @param taskCreatedTime  任务到达时间
 * @param actedTime        任务处理时间
 * @param groupId          用户组节点的用户组ID；指定用户节点为空
 * @param groupName        用户组节点的当前组名称；指定用户节点为空
 * @param currentMemberIds 用户组节点的最新有效成员
 * @param memberActions    用户组节点的成员实际动作
 * @param nodeId           逻辑节点ID（画布节点ID）；无节点快照的历史实例为空
 * @param assigneeType     指派类型，USER 或 GROUP
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
        java.util.List<ApprovalMemberActionView> memberActions,
        String nodeId,
        String assigneeType) {

    /** 兼容个人节点和旧历史数据，无用户组附加信息。 */
    public ApprovalNodeView(Integer level, String approverUserId, ApprovalNodeStatus status, String taskTitle,
                            ApprovalAction action, String comment, LocalDateTime taskCreatedTime,
                            LocalDateTime actedTime) {
        this(level, approverUserId, status, taskTitle, action, comment, taskCreatedTime, actedTime,
                null, null, java.util.List.of(), java.util.List.of());
    }

    /** 兼容不提供逻辑节点信息的调用方：节点ID为空，指派类型按是否有组信息推断。 */
    public ApprovalNodeView(Integer level, String approverUserId, ApprovalNodeStatus status, String taskTitle,
                            ApprovalAction action, String comment, LocalDateTime taskCreatedTime,
                            LocalDateTime actedTime, Long groupId, String groupName,
                            java.util.List<String> currentMemberIds,
                            java.util.List<ApprovalMemberActionView> memberActions) {
        this(level, approverUserId, status, taskTitle, action, comment, taskCreatedTime, actedTime,
                groupId, groupName, currentMemberIds, memberActions, null, groupId == null ? "USER" : "GROUP");
    }
}
