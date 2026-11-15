package com.example.template.approval.config.dto;

import java.util.List;

import com.example.template.approval.config.design.ApprovalNodeSnapshot;

/**
 * 已发布流程模板的解析结果，供审批网关发起实例时使用。
 *
 * @param templateId           模板ID
 * @param templateVersionId    生效版本ID
 * @param versionNo            生效版本号
 * @param templateName         模板名称
 * @param processDefinitionId  引擎流程定义ID
 * @param processDefinitionKey 引擎流程定义Key
 * @param approverUserIds      按路径排序的指派目标（用户ID或 {@code group:<id>} 组引用）
 * @param nodes                按路径排序的逻辑节点指派快照，与 {@code approverUserIds} 一一对应，不含组成员
 */
public record PublishedProcessTemplate(
        Long templateId,
        Long templateVersionId,
        Integer versionNo,
        String templateName,
        String processDefinitionId,
        String processDefinitionKey,
        List<String> approverUserIds,
        List<ApprovalNodeSnapshot> nodes) {

    /** 兼容不提供逻辑节点快照的调用方，逻辑节点列表为空。 */
    public PublishedProcessTemplate(Long templateId, Long templateVersionId, Integer versionNo, String templateName,
                                    String processDefinitionId, String processDefinitionKey,
                                    List<String> approverUserIds) {
        this(templateId, templateVersionId, versionNo, templateName, processDefinitionId, processDefinitionKey,
                approverUserIds, List.of());
    }
}
