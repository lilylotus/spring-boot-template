package com.example.template.approval.api.dto;

/**
 * 审批实例当前状态的对外视图。由审批能力内部完成从具体工作流引擎运行时数据到该结构的转换，
 * 业务模块只读取这一结构，不直接访问工作流引擎的运行时数据。
 *
 * @param bizType             业务动作类型
 * @param bizId               业务单据标识
 * @param status              审批状态
 * @param currentLevel        当前处于第几级审批（审批已结束时为 {@code null}）
 * @param currentApproverUserId 当前级别的指定审批人（审批已结束时为 {@code null}）
 */
public record ApprovalInstanceView(String bizType, String bizId, ApprovalStatus status, Integer currentLevel,
                                    String currentApproverUserId) {
}
