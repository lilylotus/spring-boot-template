package com.example.template.approval.api.dto;

/**
 * 发起一次审批的命令。业务模块（如 {@code identity}）只依赖该数据结构与
 * {@link com.example.template.approval.api.ApprovalGateway} 接口发起审批，
 * 不感知具体工作流引擎的运行时类型。
 *
 * @param bizType          业务动作类型，如 {@code USER_CREATE}、{@code USER_EDIT}
 * @param bizId            业务单据标识（字符串形式，由业务模块自行生成并保证在该 bizType 下唯一定位一次审批）
 * @param initiatorUserId  发起本次审批的操作人标识
 * @param payloadSnapshot  业务方随审批一并保存的内容快照（仅作为审批侧的审计信息，不参与审批流转逻辑）
 */
public record StartApprovalCommand(String bizType, String bizId, String initiatorUserId, String payloadSnapshot) {
}
