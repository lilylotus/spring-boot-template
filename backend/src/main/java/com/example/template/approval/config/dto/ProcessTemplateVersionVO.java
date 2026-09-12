package com.example.template.approval.config.dto;

import java.time.LocalDateTime;

public record ProcessTemplateVersionVO(
        Long id,
        Integer versionNo,
        String processDefinitionId,
        String processDefinitionKey,
        LocalDateTime publishedTime,
        boolean active) {
}
