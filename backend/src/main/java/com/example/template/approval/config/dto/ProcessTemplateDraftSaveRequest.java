package com.example.template.approval.config.dto;

import java.util.List;

import com.example.template.approval.config.design.ProcessDesignEdge;
import com.example.template.approval.config.design.ProcessDesignNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProcessTemplateDraftSaveRequest {
    @NotNull
    private Long expectedDraftRevision;
    @NotNull
    @Size(max = 50)
    @Valid
    private List<ProcessDesignNode> nodes;
    @NotNull
    @Size(max = 50)
    @Valid
    private List<ProcessDesignEdge> edges;
}
