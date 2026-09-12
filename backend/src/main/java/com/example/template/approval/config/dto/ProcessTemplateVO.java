package com.example.template.approval.config.dto;

import java.util.List;

import com.example.template.approval.config.design.ProcessDesignEdge;
import com.example.template.approval.config.design.ProcessDesignNode;

public record ProcessTemplateVO(
        Long templateId,
        String bizType,
        String scope,
        String name,
        Long draftRevision,
        Integer activeVersionNo,
        boolean persisted,
        List<ProcessDesignNode> nodes,
        List<ProcessDesignEdge> edges) {
}
