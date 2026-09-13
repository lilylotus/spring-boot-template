package com.example.template.approval.config.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.example.template.approval.config.design.ProcessBpmnGenerator;
import com.example.template.approval.config.design.ProcessDesignEdge;
import com.example.template.approval.config.design.ProcessDesignModel;
import com.example.template.approval.config.design.ProcessDesignNode;
import com.example.template.approval.config.design.ProcessDesignValidator;
import com.example.template.approval.config.dto.ApprovalChainLevelItem;
import com.example.template.approval.config.dto.ProcessTemplateDraftSaveRequest;
import com.example.template.approval.config.dto.ProcessTemplateVO;
import com.example.template.approval.config.dto.ProcessTemplateVersionVO;
import com.example.template.approval.config.dto.PublishedProcessTemplate;
import com.example.template.approval.config.entity.ApprovalChainConfig;
import com.example.template.approval.config.entity.ApprovalProcessTemplate;
import com.example.template.approval.config.entity.ApprovalProcessTemplateVersion;
import com.example.template.approval.config.mapper.ApprovalChainConfigMapper;
import com.example.template.approval.config.mapper.ApprovalProcessTemplateMapper;
import com.example.template.approval.config.mapper.ApprovalProcessTemplateVersionMapper;
import com.example.template.approval.config.publish.ProcessDefinitionPublisher;
import com.example.template.approval.config.service.ApprovalConfigService;
import com.example.template.approval.config.service.ApprovalProcessTemplateService;
import com.example.template.common.BusinessException;
import com.example.template.util.JacksonUtils;
import com.example.template.usergroup.service.UserGroupService;
import com.example.template.approval.config.design.GroupAssignment;

/** 管理草稿、发布版本及业务映射，通过发布适配器部署并校验实时组可用性。 */
@Service
@RequiredArgsConstructor
public class ApprovalProcessTemplateServiceImpl implements ApprovalProcessTemplateService {
    public static final String GLOBAL_SCOPE = "GLOBAL";
    private static final Set<String> LEGACY_BIZ_TYPES = Set.of("USER_CREATE", "USER_EDIT");
    private final ApprovalProcessTemplateMapper templateMapper;
    private final ApprovalProcessTemplateVersionMapper versionMapper;
    private final ApprovalChainConfigMapper chainMapper;
    private final ApprovalConfigService approvalConfigService;
    private final ProcessDesignValidator validator;
    private final ProcessBpmnGenerator bpmnGenerator;
    private final ProcessDefinitionPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final UserGroupService userGroupService;

    /** 查询业务模板草稿，未持久化时从旧链构建初始画布。 */
    @Override
    public ProcessTemplateVO getTemplate(String bizType, String scope) {
        validateKey(bizType, scope);
        ApprovalProcessTemplate template = findTemplate(bizType, scope);
        if (template == null) {
            return toView(null, bizType, scope, displayName(bizType), buildLegacyModel(bizType), false);
        }
        return toView(template, bizType, scope, template.getName(), parseModel(template.getDraftModelJson()), true);
    }

    /** 按修订号保存草稿，冲突时拒绝覆盖其他编辑者的内容。 */
    @Override
    @Transactional
    public ProcessTemplateVO saveDraft(String bizType, String scope, ProcessTemplateDraftSaveRequest request) {
        validateKey(bizType, scope);
        ProcessDesignModel model = new ProcessDesignModel();
        model.setNodes(request.getNodes());
        model.setEdges(request.getEdges());
        String json = JacksonUtils.toJson(model);
        ApprovalProcessTemplate existing = findTemplate(bizType, scope);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            if (request.getExpectedDraftRevision() != 0) {
                throw new BusinessException("流程草稿已变化，请重新加载");
            }
            ApprovalProcessTemplate created = new ApprovalProcessTemplate();
            created.setBizType(bizType);
            created.setScopeKey(scope);
            created.setName(displayName(bizType));
            created.setDraftModelJson(json);
            created.setDraftRevision(1L);
            created.setCreatedTime(now);
            created.setUpdatedTime(now);
            templateMapper.insert(created);
        } else {
            ApprovalProcessTemplate update = new ApprovalProcessTemplate();
            update.setDraftModelJson(json);
            update.setDraftRevision(existing.getDraftRevision() + 1);
            update.setUpdatedTime(now);
            int affected = templateMapper.update(update,
                    Wrappers.<ApprovalProcessTemplate>lambdaUpdate()
                            .eq(ApprovalProcessTemplate::getId, existing.getId())
                            .eq(ApprovalProcessTemplate::getDraftRevision, request.getExpectedDraftRevision()));
            if (affected == 0) {
                throw new BusinessException("流程草稿已被其他会话修改，请重新加载");
            }
        }
        return getTemplate(bizType, scope);
    }

    /** 校验并发布流程版本，持久化失败时补偿删除新部署。 */
    @Override
    public ProcessTemplateVO publish(String bizType, String scope, long expectedDraftRevision) {
        validateKey(bizType, scope);
        ApprovalProcessTemplate template = findTemplate(bizType, scope);
        if (template == null || template.getDraftRevision() != expectedDraftRevision) {
            throw new BusinessException("流程草稿已变化，请重新加载后发布");
        }
        ProcessDesignModel model = parseModel(template.getDraftModelJson());
        List<String> approvers = validator.validateAndResolveApprovers(model);
        validateGroups(approvers);
        String processKey = "approvalTemplate_" + template.getId();
        String bpmnXml = bpmnGenerator.generate(processKey, template.getName());
        ProcessDefinitionPublisher.PublishedDefinition deployed = publisher.deploy(
                template.getName(), processKey + ".bpmn20.xml", bpmnXml);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ApprovalProcessTemplate current = templateMapper.selectById(template.getId());
                if (current == null || current.getDraftRevision() != expectedDraftRevision) {
                    throw new BusinessException("流程草稿已变化，请重新加载后发布");
                }
                Integer maxVersion = versionMapper.selectList(
                                Wrappers.<ApprovalProcessTemplateVersion>lambdaQuery()
                                        .eq(ApprovalProcessTemplateVersion::getTemplateId, template.getId())
                                        .orderByDesc(ApprovalProcessTemplateVersion::getVersionNo)
                                        .last("LIMIT 1"))
                        .stream().findFirst().map(ApprovalProcessTemplateVersion::getVersionNo).orElse(0);
                ApprovalProcessTemplateVersion version = new ApprovalProcessTemplateVersion();
                version.setTemplateId(template.getId());
                version.setVersionNo(maxVersion + 1);
                version.setModelJson(JacksonUtils.toJson(model));
                version.setBpmnXml(bpmnXml);
                version.setDeploymentId(deployed.deploymentId());
                version.setProcessDefinitionId(deployed.processDefinitionId());
                version.setProcessDefinitionKey(deployed.processDefinitionKey());
                version.setPublishedTime(LocalDateTime.now());
                versionMapper.insert(version);

                ApprovalProcessTemplate activate = new ApprovalProcessTemplate();
                activate.setId(template.getId());
                activate.setActiveVersionId(version.getId());
                activate.setUpdatedTime(LocalDateTime.now());
                templateMapper.updateById(activate);

                List<ApprovalChainLevelItem> levels = new ArrayList<>();
                for (int index = 0; index < approvers.size(); index++) {
                    ApprovalChainLevelItem item = new ApprovalChainLevelItem();
                    item.setLevelNo(index + 1);
                    item.setApproverUserId(approvers.get(index));
                    levels.add(item);
                }
                approvalConfigService.saveChain(bizType, levels);
            });
        } catch (RuntimeException exception) {
            publisher.deleteDeployment(deployed.deploymentId());
            throw exception;
        }
        return getTemplate(bizType, scope);
    }

    /** 按业务映射解析当前版本，启动前验证用户组仍可用。 */
    @Override
    public PublishedProcessTemplate resolve(String bizType, String scope) {
        validateKey(bizType, scope);
        ApprovalProcessTemplate template = findTemplate(bizType, scope);
        if (template == null && LEGACY_BIZ_TYPES.contains(bizType)) {
            initializeLegacyTemplate(bizType, scope);
            template = findTemplate(bizType, scope);
        }
        if (template == null || template.getActiveVersionId() == null) {
            throw new BusinessException("业务动作[" + bizType + "]尚未发布审批流程");
        }
        ApprovalProcessTemplateVersion version = versionMapper.selectById(template.getActiveVersionId());
        if (version == null) {
            throw new BusinessException("业务动作[" + bizType + "]的生效流程版本不存在");
        }
        List<String> approvers = validator.validateAndResolveApprovers(parseModel(version.getModelJson()));
        validateGroups(approvers);
        return new PublishedProcessTemplate(template.getId(), version.getId(), version.getVersionNo(),
                template.getName(), version.getProcessDefinitionId(), version.getProcessDefinitionKey(), approvers);
    }

    /** 查询不可变发布历史，标识当前激活版本。 */
    @Override
    public List<ProcessTemplateVersionVO> listVersions(String bizType, String scope) {
        validateKey(bizType, scope);
        ApprovalProcessTemplate template = findTemplate(bizType, scope);
        if (template == null) {
            return List.of();
        }
        Long activeVersionId = template.getActiveVersionId();
        return versionMapper.selectList(Wrappers.<ApprovalProcessTemplateVersion>lambdaQuery()
                        .eq(ApprovalProcessTemplateVersion::getTemplateId, template.getId())
                        .orderByDesc(ApprovalProcessTemplateVersion::getVersionNo))
                .stream()
                .map(version -> new ProcessTemplateVersionVO(version.getId(), version.getVersionNo(),
                        version.getProcessDefinitionId(), version.getProcessDefinitionKey(),
                        version.getPublishedTime(), version.getId().equals(activeVersionId)))
                .toList();
    }

    /** 判断指定业务及作用域是否已经发布模板。 */
    @Override
    public boolean hasPublishedTemplate(String bizType, String scope) {
        ApprovalProcessTemplate template = findTemplate(bizType, scope);
        return template != null && template.getActiveVersionId() != null;
    }

    /** 查询唯一业务映射模板。 */
    private ApprovalProcessTemplate findTemplate(String bizType, String scope) {
        return templateMapper.selectOne(Wrappers.<ApprovalProcessTemplate>lambdaQuery()
                .eq(ApprovalProcessTemplate::getBizType, bizType)
                .eq(ApprovalProcessTemplate::getScopeKey, scope));
    }

    /** 为尚无模板的历史受控业务初始化并发布个人审批链。 */
    private synchronized void initializeLegacyTemplate(String bizType, String scope) {
        ApprovalProcessTemplate current = findTemplate(bizType, scope);
        if (current != null) {
            return;
        }
        List<ApprovalChainConfig> chain = chainMapper.selectList(Wrappers.<ApprovalChainConfig>lambdaQuery()
                .eq(ApprovalChainConfig::getBizType, bizType).orderByAsc(ApprovalChainConfig::getLevelNo));
        if (chain.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        ApprovalProcessTemplate template = new ApprovalProcessTemplate();
        template.setBizType(bizType);
        template.setScopeKey(scope);
        template.setName(displayName(bizType));
        template.setDraftModelJson(JacksonUtils.toJson(buildLegacyModel(bizType)));
        template.setDraftRevision(1L);
        template.setCreatedTime(now);
        template.setUpdatedTime(now);
        templateMapper.insert(template);
        publish(bizType, scope, template.getDraftRevision());
    }

    /** 组装模板、草稿及当前发布版本信息。 */
    private ProcessTemplateVO toView(ApprovalProcessTemplate template, String bizType, String scope,
                                     String name, ProcessDesignModel model, boolean persisted) {
        Integer activeVersion = null;
        if (template != null && template.getActiveVersionId() != null) {
            ApprovalProcessTemplateVersion version = versionMapper.selectById(template.getActiveVersionId());
            activeVersion = version == null ? null : version.getVersionNo();
        }
        return new ProcessTemplateVO(template == null ? null : template.getId(), bizType, scope, name,
                template == null ? 0L : template.getDraftRevision(), activeVersion, persisted,
                model.getNodes(), model.getEdges());
    }

    /** 将旧顺序链转换为设计器初始节点及连线。 */
    private ProcessDesignModel buildLegacyModel(String bizType) {
        List<ApprovalChainConfig> chain = chainMapper.selectList(Wrappers.<ApprovalChainConfig>lambdaQuery()
                .eq(ApprovalChainConfig::getBizType, bizType).orderByAsc(ApprovalChainConfig::getLevelNo));
        ProcessDesignModel model = new ProcessDesignModel();
        List<ProcessDesignNode> nodes = new ArrayList<>();
        List<ProcessDesignEdge> edges = new ArrayList<>();
        nodes.add(node("start", "START", 40, 120, null));
        String previous = "start";
        for (int index = 0; index < chain.size(); index++) {
            String id = "approval-" + (index + 1);
            nodes.add(node(id, "APPROVAL", 240 + index * 220, 120, chain.get(index).getApproverUserId()));
            edges.add(edge("edge-" + previous + "-" + id, previous, id));
            previous = id;
        }
        String endId = "end";
        nodes.add(node(endId, "END", 240 + chain.size() * 220, 120, null));
        if (!chain.isEmpty()) {
            edges.add(edge("edge-" + previous + "-end", previous, endId));
        }
        model.setNodes(nodes);
        model.setEdges(edges);
        return model;
    }

    /** 构造兼容旧链的节点，保留组引用类型。 */
    private ProcessDesignNode node(String id, String type, double x, double y, String approver) {
        ProcessDesignNode node = new ProcessDesignNode();
        node.setId(id); node.setType(type); node.setX(x); node.setY(y);
        if (GroupAssignment.isGroup(approver)) {
            node.setAssigneeType("GROUP");
            node.setApproverGroupId(GroupAssignment.id(approver));
        } else {
            node.setApproverUserId(approver);
        }
        return node;
    }

    /** 构造有向连线。 */
    private ProcessDesignEdge edge(String id, String source, String target) {
        ProcessDesignEdge edge = new ProcessDesignEdge();
        edge.setId(id); edge.setSourceNodeId(source); edge.setTargetNodeId(target);
        return edge;
    }

    /** 解析持久化模型，损坏时报告业务异常。 */
    private ProcessDesignModel parseModel(String json) {
        try {
            return JacksonUtils.toObj(json, ProcessDesignModel.class);
        } catch (RuntimeException exception) {
            throw new BusinessException("流程模型数据损坏");
        }
    }

    /** 校验业务类型长度及当前支持的GLOBAL范围。 */
    private void validateKey(String bizType, String scope) {
        if (!StringUtils.hasText(bizType) || bizType.length() > 64) {
            throw new BusinessException("业务类型格式不正确");
        }
        if (!GLOBAL_SCOPE.equals(scope)) {
            throw new BusinessException("当前仅支持 GLOBAL 作用域");
        }
    }

    /** 校验发布或发起时引用组可用，不保存有效成员集合。 */
    private void validateGroups(List<String> assignments) {
        assignments.stream().filter(GroupAssignment::isGroup)
                .forEach(assignment -> userGroupService.requireAvailable(GroupAssignment.id(assignment)));
    }

    /** 生成默认业务流程名称。 */
    private String displayName(String bizType) {
        return switch (bizType) {
            case "USER_CREATE" -> "用户新增审批流程";
            case "USER_EDIT" -> "用户编辑审批流程";
            default -> bizType + "审批流程";
        };
    }
}
