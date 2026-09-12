package com.example.template.approval.config.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProcessTemplatePublishRequest {
    @NotNull
    private Long expectedDraftRevision;
}
