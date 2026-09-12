package com.example.template.approval.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单次审批实例的完整详情视图。
 *
 * @param approvalId      稳定的审批实例标识
 * @param bizType         业务动作类型
 * @param bizId           业务单据标识
 * @param status          审批整体状态
 * @param currentLevel    当前审批级别；已结束时为空
 * @param initiatorUserId 发起人标识；不可恢复时为空
 * @param submittedTime   审批提交时间
 * @param payloadSnapshot 发起时业务数据 JSON；不可恢复时为空
 * @param payloadAvailable 业务数据快照是否可用
 * @param nodes            发起时审批链对应的节点列表
 */
public record ApprovalDetailView(
        Long approvalId,
        String bizType,
        String bizId,
        ApprovalStatus status,
        Integer currentLevel,
        String initiatorUserId,
        LocalDateTime submittedTime,
        String payloadSnapshot,
        boolean payloadAvailable,
        List<ApprovalNodeView> nodes,
        String templateName,
        Integer templateVersionNo) {
}
