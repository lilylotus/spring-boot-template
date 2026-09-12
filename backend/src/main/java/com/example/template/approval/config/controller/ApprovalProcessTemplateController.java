package com.example.template.approval.config.controller;

import java.util.List;

import com.example.template.approval.config.dto.ProcessTemplateDraftSaveRequest;
import com.example.template.approval.config.dto.ProcessTemplatePublishRequest;
import com.example.template.approval.config.dto.ProcessTemplateVO;
import com.example.template.approval.config.dto.ProcessTemplateVersionVO;
import com.example.template.approval.config.service.ApprovalProcessTemplateService;
import com.example.template.common.RestResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ApprovalProcessTemplateController {
    private final ApprovalProcessTemplateService templateService;

    @GetMapping("/api/approval/templates/{bizType}")
    public RestResult<ProcessTemplateVO> getTemplate(@PathVariable String bizType,
                                                      @RequestParam(defaultValue = "GLOBAL") String scope) {
        return RestResult.success(templateService.getTemplate(bizType, scope));
    }

    @PutMapping("/api/approval/templates/{bizType}/draft")
    public RestResult<ProcessTemplateVO> saveDraft(@PathVariable String bizType,
                                                   @RequestParam(defaultValue = "GLOBAL") String scope,
                                                   @Valid @RequestBody ProcessTemplateDraftSaveRequest request) {
        return RestResult.success(templateService.saveDraft(bizType, scope, request));
    }

    @PostMapping("/api/approval/templates/{bizType}/publish")
    public RestResult<ProcessTemplateVO> publish(@PathVariable String bizType,
                                                 @RequestParam(defaultValue = "GLOBAL") String scope,
                                                 @Valid @RequestBody ProcessTemplatePublishRequest request) {
        return RestResult.success(templateService.publish(bizType, scope, request.getExpectedDraftRevision()));
    }

    @GetMapping("/api/approval/templates/{bizType}/versions")
    public RestResult<List<ProcessTemplateVersionVO>> listVersions(
            @PathVariable String bizType,
            @RequestParam(defaultValue = "GLOBAL") String scope) {
        return RestResult.success(templateService.listVersions(bizType, scope));
    }
}
