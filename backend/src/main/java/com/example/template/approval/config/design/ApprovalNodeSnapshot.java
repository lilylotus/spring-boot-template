package com.example.template.approval.config.design;

/**
 * 审批域逻辑节点指派快照：描述发布时画布上的一个审批节点由谁处理。
 * <p>
 * 只保存节点结构（画布节点ID、指派类型、指定用户ID或用户组ID），从不包含用户组成员列表；
 * 组成员在每次查询待办、查看详情和提交审批时由用户组服务实时读取。
 *
 * @param nodeId         画布逻辑节点ID
 * @param assigneeType   指派类型，取值 {@link #TYPE_USER} 或 {@link #TYPE_GROUP}
 * @param approverUserId 指定用户节点的审批人标识；用户组节点为空
 * @param groupId        用户组节点引用的用户组ID；指定用户节点为空
 */
public record ApprovalNodeSnapshot(String nodeId, String assigneeType, String approverUserId, Long groupId) {

    /** 指派类型：指定用户。 */
    public static final String TYPE_USER = "USER";

    /** 指派类型：用户组。 */
    public static final String TYPE_GROUP = "GROUP";
}
