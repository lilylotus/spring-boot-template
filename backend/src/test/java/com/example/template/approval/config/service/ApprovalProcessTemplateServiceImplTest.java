package com.example.template.approval.config.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.template.approval.config.design.ProcessBpmnGenerator;
import com.example.template.approval.config.design.ProcessDesignEdge;
import com.example.template.approval.config.design.ProcessDesignModel;
import com.example.template.approval.config.design.ProcessDesignNode;
import com.example.template.approval.config.design.ProcessDesignValidator;
import com.example.template.approval.config.entity.ApprovalProcessTemplate;
import com.example.template.approval.config.entity.ApprovalProcessTemplateVersion;
import com.example.template.approval.config.mapper.ApprovalChainConfigMapper;
import com.example.template.approval.config.mapper.ApprovalProcessTemplateMapper;
import com.example.template.approval.config.mapper.ApprovalProcessTemplateVersionMapper;
import com.example.template.approval.config.publish.ProcessDefinitionPublisher;
import com.example.template.approval.config.service.impl.ApprovalProcessTemplateServiceImpl;
import com.example.template.common.BusinessException;
import com.example.template.util.JacksonUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalProcessTemplateServiceImplTest {

    @Mock private ApprovalProcessTemplateMapper templateMapper;
    @Mock private ApprovalProcessTemplateVersionMapper versionMapper;
    @Mock private ApprovalChainConfigMapper chainMapper;
    @Mock private ApprovalConfigService approvalConfigService;
    @Mock private ProcessDefinitionPublisher publisher;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private TransactionStatus transactionStatus;

    private ApprovalProcessTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApprovalProcessTemplateServiceImpl(templateMapper, versionMapper, chainMapper,
                approvalConfigService, new ProcessDesignValidator(), new ProcessBpmnGenerator(),
                publisher, transactionTemplate);
    }

    @Test
    void publish_whenVersionPersistenceFails_deletesNewDeployment() {
        ApprovalProcessTemplate template = template(false);
        when(templateMapper.selectOne(any())).thenReturn(template);
        when(templateMapper.selectById(7L)).thenReturn(template);
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(publisher.deploy(any(), any(), any())).thenReturn(
                new ProcessDefinitionPublisher.PublishedDefinition("deployment-1", "definition-1", "key-1"));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0, Consumer.class);
            callback.accept(transactionStatus);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(versionMapper.insert(any(ApprovalProcessTemplateVersion.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.publish("USER_CREATE", "GLOBAL", 3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        verify(publisher).deleteDeployment("deployment-1");
    }

    @Test
    void resolve_doesNotAutoPublishPersistedDraft() {
        when(templateMapper.selectOne(any())).thenReturn(template(false));

        assertThatThrownBy(() -> service.resolve("USER_CREATE", "GLOBAL"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("业务动作[USER_CREATE]尚未发布审批流程");

        verifyNoInteractions(publisher);
    }

    private ApprovalProcessTemplate template(boolean active) {
        ApprovalProcessTemplate template = new ApprovalProcessTemplate();
        template.setId(7L);
        template.setBizType("USER_CREATE");
        template.setScopeKey("GLOBAL");
        template.setName("用户新增审批流程");
        template.setDraftRevision(3L);
        template.setDraftModelJson(JacksonUtils.toJson(validModel()));
        template.setActiveVersionId(active ? 11L : null);
        template.setCreatedTime(LocalDateTime.now());
        template.setUpdatedTime(LocalDateTime.now());
        return template;
    }

    private ProcessDesignModel validModel() {
        ProcessDesignNode start = node("start", "START", null);
        ProcessDesignNode approval = node("approval", "APPROVAL", "alice");
        ProcessDesignNode end = node("end", "END", null);
        ProcessDesignModel model = new ProcessDesignModel();
        model.setNodes(List.of(start, approval, end));
        model.setEdges(List.of(edge("start", "approval"), edge("approval", "end")));
        return model;
    }

    private ProcessDesignNode node(String id, String type, String approver) {
        ProcessDesignNode node = new ProcessDesignNode();
        node.setId(id);
        node.setType(type);
        node.setApproverUserId(approver);
        return node;
    }

    private ProcessDesignEdge edge(String source, String target) {
        ProcessDesignEdge edge = new ProcessDesignEdge();
        edge.setId(source + "-" + target);
        edge.setSourceNodeId(source);
        edge.setTargetNodeId(target);
        return edge;
    }
}
