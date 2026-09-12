package com.example.template.approval.config.service;

import java.util.List;

import com.example.template.approval.config.dto.ProcessTemplateDraftSaveRequest;
import com.example.template.approval.config.dto.ProcessTemplateVO;
import com.example.template.approval.config.dto.ProcessTemplateVersionVO;

public interface ApprovalProcessTemplateService extends PublishedProcessResolver {
    ProcessTemplateVO getTemplate(String bizType, String scope);

    ProcessTemplateVO saveDraft(String bizType, String scope, ProcessTemplateDraftSaveRequest request);

    ProcessTemplateVO publish(String bizType, String scope, long expectedDraftRevision);

    List<ProcessTemplateVersionVO> listVersions(String bizType, String scope);

    boolean hasPublishedTemplate(String bizType, String scope);
}
