package com.example.template.approval.config.dto;

import java.util.List;

public record PublishedProcessTemplate(
        Long templateId,
        Long templateVersionId,
        Integer versionNo,
        String templateName,
        String processDefinitionId,
        String processDefinitionKey,
        List<String> approverUserIds) {
}
