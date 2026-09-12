package com.example.template.approval.engine.flowable;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.template.approval.api.ApprovalGateway;
import com.example.template.approval.api.dto.ApprovalActCommand;
import com.example.template.approval.api.dto.ApprovalAction;
import com.example.template.approval.api.dto.ApprovalDetailView;
import com.example.template.approval.api.dto.ApprovalInstanceView;
import com.example.template.approval.api.dto.ApprovalNodeStatus;
import com.example.template.approval.api.dto.ApprovalNodeView;
import com.example.template.approval.api.dto.ApprovalRecordView;
import com.example.template.approval.api.dto.ApprovalStatus;
import com.example.template.approval.api.dto.ApprovalTaskView;
import com.example.template.approval.api.dto.StartApprovalCommand;
import com.example.template.approval.config.dto.PublishedProcessTemplate;
import com.example.template.approval.config.service.PublishedProcessResolver;
import com.example.template.approval.link.entity.ApprovalBizLink;
import com.example.template.approval.link.service.ApprovalBizLinkService;
import com.example.template.approval.record.entity.ApprovalActionRecord;
import com.example.template.approval.record.service.ApprovalActionRecordService;
import com.example.template.common.BusinessException;
import com.example.template.util.JacksonUtils;

/**
 * {@link ApprovalGateway} 的 Flowable 实现，是本项目内唯一允许出现 {@code org.flowable.*}
 * import 的包（{@code approval.engine.flowable}）中的核心类。业务模块不感知本类的存在，
 * 只依赖 {@link ApprovalGateway} 接口。
 */
@Service
@RequiredArgsConstructor
public class FlowableApprovalGateway implements ApprovalGateway {

    private static final String GLOBAL_SCOPE = "GLOBAL";

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final PublishedProcessResolver publishedProcessResolver;
    private final ApprovalBizLinkService approvalBizLinkService;
    private final ApprovalActionRecordService approvalActionRecordService;

    @Override
    @Transactional
    public ApprovalInstanceView start(StartApprovalCommand command) {
        PublishedProcessTemplate template = publishedProcessResolver.resolve(
                command.bizType(), GLOBAL_SCOPE);
        List<String> approverList = template.approverUserIds();

        Map<String, Object> variables = new HashMap<>();
        variables.put("approverList", approverList);
        variables.put("bizType", command.bizType());
        variables.put("bizId", command.bizId());
        variables.put("initiatorUserId", command.initiatorUserId());
        variables.put("payloadSnapshot", command.payloadSnapshot());

        String businessKey = command.bizType() + ":" + command.bizId();
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(
                template.processDefinitionId(), businessKey, variables);

        ApprovalBizLink link = new ApprovalBizLink();
        link.setBizType(command.bizType());
        link.setBizId(command.bizId());
        link.setProcessInstanceId(processInstance.getId());
        link.setBusinessKey(businessKey);
        link.setTemplateId(template.templateId());
        link.setTemplateName(template.templateName());
        link.setTemplateVersionId(template.templateVersionId());
        link.setTemplateVersionNo(template.versionNo());
        link.setProcessDefinitionId(template.processDefinitionId());
        link.setStatus(ApprovalStatus.PENDING.name());
        link.setInitiatorUserId(command.initiatorUserId());
        link.setApproverSnapshot(JacksonUtils.toJson(approverList));
        link.setPayloadSnapshot(command.payloadSnapshot());
        approvalBizLinkService.save(link);

        return new ApprovalInstanceView(command.bizType(), command.bizId(), ApprovalStatus.PENDING, 1,
                approverList.get(0));
    }

    @Override
    public ApprovalInstanceView queryByBizKey(String bizType, String bizId) {
        ApprovalBizLink link = approvalBizLinkService.findLatestByBizKey(bizType, bizId).orElse(null);
        if (link == null) {
            return null;
        }
        if (!ApprovalStatus.PENDING.name().equals(link.getStatus())) {
            return new ApprovalInstanceView(bizType, bizId, ApprovalStatus.valueOf(link.getStatus()), null, null);
        }

        Task activeTask = taskService.createTaskQuery()
                .processInstanceId(link.getProcessInstanceId())
                .singleResult();
        if (activeTask == null) {
            // 理论不应出现：流程已无活动任务但关联表状态还未被 end 监听器更新，兜底按进行中返回
            return new ApprovalInstanceView(bizType, bizId, ApprovalStatus.PENDING, null, null);
        }
        return new ApprovalInstanceView(bizType, bizId, ApprovalStatus.PENDING, resolveLevel(activeTask),
                activeTask.getAssignee());
    }

    @Override
    @Transactional
    public void act(ApprovalActCommand command) {
        ApprovalBizLink link = approvalBizLinkService.findLatestByBizKey(command.bizType(), command.bizId())
                .filter(l -> ApprovalStatus.PENDING.name().equals(l.getStatus()))
                .orElseThrow(() -> new BusinessException(
                        "未找到进行中的审批实例：" + command.bizType() + ":" + command.bizId()));

        Task task = taskService.createTaskQuery()
                .processInstanceId(link.getProcessInstanceId())
                .taskAssignee(command.approverUserId())
                .singleResult();
        if (task == null) {
            throw new BusinessException("当前审批人无待处理任务，或该任务已被处理");
        }

        ApprovalActionRecord record = new ApprovalActionRecord();
        record.setBizType(link.getBizType());
        record.setBizId(link.getBizId());
        record.setProcessInstanceId(link.getProcessInstanceId());
        record.setTaskId(task.getId());
        record.setApproverUserId(command.approverUserId());
        record.setLevelNo(resolveLevel(task));
        record.setTaskTitle(task.getName());
        record.setAction(command.action().name());
        record.setComment(command.comment());
        record.setTaskCreatedTime(toLocalDateTime(task));
        approvalActionRecordService.save(record);

        Map<String, Object> variables = new HashMap<>();
        variables.put("approvalAction", command.action().name());
        variables.put("approvalComment", command.comment());
        taskService.complete(task.getId(), variables);
    }

    @Override
    public List<ApprovalTaskView> listPendingTasks(String approverUserId) {
        List<Task> tasks = taskService.createTaskQuery()
                .taskAssignee(approverUserId)
                .orderByTaskCreateTime().asc()
                .list();

        List<ApprovalTaskView> result = new ArrayList<>();
        for (Task task : tasks) {
            ApprovalBizLink link = approvalBizLinkService.findByProcessInstanceId(task.getProcessInstanceId())
                    .orElse(null);
            if (link == null) {
                continue;
            }
            LocalDateTime createdTime = toLocalDateTime(task);
            result.add(new ApprovalTaskView(link.getId(), link.getBizType(), link.getBizId(), resolveLevel(task),
                    task.getName(), createdTime));
        }
        return result;
    }

    /**
     * 查询指定审批人的已完成审批记录。
     *
     * @param approverUserId 审批人标识
     * @return 不依赖 Flowable 类型的审批记录视图
     */
    @Override
    public List<ApprovalRecordView> listApprovalRecords(String approverUserId) {
        return approvalActionRecordService.listByApproverUserId(approverUserId).stream()
                .map(this::toApprovalRecordView)
                .toList();
    }

    /**
     * 查询并组装单次审批详情，只允许当前待办审批人或已处理审批人访问。
     *
     * @param approvalId 审批实例标识
     * @param viewerUserId 当前查看人标识
     * @return 审批实例详情
     * @throws BusinessException 审批实例不存在或查看人未参与该实例
     */
    @Override
    public ApprovalDetailView getApprovalDetail(Long approvalId, String viewerUserId) {
        ApprovalBizLink link = approvalBizLinkService.findById(approvalId)
                .orElseThrow(() -> new BusinessException("审批实例不存在：" + approvalId));
        Task activeTask = findActiveTask(link.getProcessInstanceId());
        List<ApprovalActionRecord> actionRecords = approvalActionRecordService
                .listByProcessInstanceId(link.getProcessInstanceId());
        validateDetailAccess(viewerUserId, activeTask, actionRecords);

        List<String> approvers = resolveApprovers(link);
        String payloadSnapshot = resolvePayloadSnapshot(link);
        String initiatorUserId = link.getInitiatorUserId() != null
                ? link.getInitiatorUserId() : toNullableString(resolveFlowableVariable(
                        link.getProcessInstanceId(), "initiatorUserId"));
        ApprovalStatus status = ApprovalStatus.valueOf(link.getStatus());
        Integer currentLevel = activeTask == null ? null : resolveLevel(activeTask);
        List<ApprovalNodeView> nodes = buildNodes(approvers, actionRecords, activeTask, status, currentLevel);

        return new ApprovalDetailView(
                link.getId(),
                link.getBizType(),
                link.getBizId(),
                status,
                currentLevel,
                initiatorUserId,
                link.getCreatedTime(),
                payloadSnapshot,
                payloadSnapshot != null,
                nodes,
                link.getTemplateName(),
                link.getTemplateVersionNo());
    }

    private ApprovalRecordView toApprovalRecordView(ApprovalActionRecord record) {
        Long approvalId = approvalBizLinkService.findByProcessInstanceId(record.getProcessInstanceId())
                .map(ApprovalBizLink::getId)
                .orElse(null);
        return new ApprovalRecordView(
                approvalId,
                record.getBizType(),
                record.getBizId(),
                record.getLevelNo(),
                record.getTaskTitle(),
                ApprovalAction.valueOf(record.getAction()),
                record.getComment(),
                record.getTaskCreatedTime(),
                record.getActedTime());
    }

    private Task findActiveTask(String processInstanceId) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
    }

    private void validateDetailAccess(
            String viewerUserId,
            Task activeTask,
            List<ApprovalActionRecord> actionRecords) {
        boolean isCurrentApprover = activeTask != null && viewerUserId.equals(activeTask.getAssignee());
        boolean hasProcessed = actionRecords.stream()
                .anyMatch(record -> viewerUserId.equals(record.getApproverUserId()));
        if (!isCurrentApprover && !hasProcessed) {
            throw new BusinessException("无权查看该审批详情");
        }
    }

    private List<String> resolveApprovers(ApprovalBizLink link) {
        if (link.getApproverSnapshot() != null) {
            return JacksonUtils.toObj(link.getApproverSnapshot(), JacksonUtils.LIST_STRING_TYPE_REFERENCE);
        }
        Object legacyApprovers = resolveFlowableVariable(link.getProcessInstanceId(), "approverList");
        if (legacyApprovers == null) {
            return List.of();
        }
        if (legacyApprovers instanceof String json) {
            return JacksonUtils.toObj(json, JacksonUtils.LIST_STRING_TYPE_REFERENCE);
        }
        return JacksonUtils.convert(legacyApprovers, JacksonUtils.LIST_STRING_TYPE_REFERENCE);
    }

    private String resolvePayloadSnapshot(ApprovalBizLink link) {
        if (link.getPayloadSnapshot() != null) {
            return link.getPayloadSnapshot();
        }
        Object legacyPayload = resolveFlowableVariable(link.getProcessInstanceId(), "payloadSnapshot");
        return toNullableString(legacyPayload);
    }

    private Object resolveFlowableVariable(String processInstanceId, String variableName) {
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (processInstance != null) {
            Object runtimeValue = runtimeService.getVariable(processInstanceId, variableName);
            if (runtimeValue != null) {
                return runtimeValue;
            }
        }
        HistoricVariableInstance historicVariable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .variableName(variableName)
                .singleResult();
        return historicVariable == null ? null : historicVariable.getValue();
    }

    private String toNullableString(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof String stringValue ? stringValue : JacksonUtils.toJson(value);
    }

    private List<ApprovalNodeView> buildNodes(
            List<String> approvers,
            List<ApprovalActionRecord> actionRecords,
            Task activeTask,
            ApprovalStatus approvalStatus,
            Integer currentLevel) {
        Map<Integer, ApprovalActionRecord> recordByLevel = new LinkedHashMap<>();
        for (ApprovalActionRecord record : actionRecords) {
            recordByLevel.putIfAbsent(record.getLevelNo(), record);
        }
        int nodeCount = approvers.size();
        for (ApprovalActionRecord record : actionRecords) {
            nodeCount = Math.max(nodeCount, record.getLevelNo());
        }
        if (currentLevel != null) {
            nodeCount = Math.max(nodeCount, currentLevel);
        }

        Integer rejectedLevel = actionRecords.stream()
                .filter(record -> ApprovalAction.REJECT.name().equals(record.getAction()))
                .map(ApprovalActionRecord::getLevelNo)
                .findFirst()
                .orElse(null);
        List<ApprovalNodeView> nodes = new ArrayList<>();
        for (int level = 1; level <= nodeCount; level++) {
            ApprovalActionRecord record = recordByLevel.get(level);
            boolean isCurrent = currentLevel != null && currentLevel == level;
            String approverUserId = resolveNodeApprover(approvers, record, activeTask, level, isCurrent);
            ApprovalNodeStatus nodeStatus = resolveNodeStatus(record, approvalStatus, rejectedLevel, level, isCurrent);
            nodes.add(toApprovalNodeView(level, approverUserId, nodeStatus, record, activeTask, isCurrent));
        }
        return nodes;
    }

    private String resolveNodeApprover(
            List<String> approvers,
            ApprovalActionRecord record,
            Task activeTask,
            int level,
            boolean isCurrent) {
        if (level <= approvers.size()) {
            return approvers.get(level - 1);
        }
        if (record != null) {
            return record.getApproverUserId();
        }
        return isCurrent && activeTask != null ? activeTask.getAssignee() : null;
    }

    private ApprovalNodeStatus resolveNodeStatus(
            ApprovalActionRecord record,
            ApprovalStatus approvalStatus,
            Integer rejectedLevel,
            int level,
            boolean isCurrent) {
        if (record != null) {
            return ApprovalAction.AGREE.name().equals(record.getAction())
                    ? ApprovalNodeStatus.APPROVED : ApprovalNodeStatus.REJECTED;
        }
        if (isCurrent) {
            return ApprovalNodeStatus.PENDING;
        }
        if (approvalStatus == ApprovalStatus.REJECTED && rejectedLevel != null && level > rejectedLevel) {
            return ApprovalNodeStatus.SKIPPED;
        }
        return ApprovalNodeStatus.WAITING;
    }

    private ApprovalNodeView toApprovalNodeView(
            int level,
            String approverUserId,
            ApprovalNodeStatus nodeStatus,
            ApprovalActionRecord record,
            Task activeTask,
            boolean isCurrent) {
        if (record != null) {
            return new ApprovalNodeView(
                    level,
                    approverUserId,
                    nodeStatus,
                    record.getTaskTitle(),
                    ApprovalAction.valueOf(record.getAction()),
                    record.getComment(),
                    record.getTaskCreatedTime(),
                    record.getActedTime());
        }
        return new ApprovalNodeView(
                level,
                approverUserId,
                nodeStatus,
                isCurrent && activeTask != null ? activeTask.getName() : null,
                null,
                null,
                isCurrent && activeTask != null ? toLocalDateTime(activeTask) : null,
                null);
    }

    private LocalDateTime toLocalDateTime(Task task) {
        return task.getCreateTime() == null ? null
                : LocalDateTime.ofInstant(task.getCreateTime().toInstant(), ZoneId.systemDefault());
    }

    private int resolveLevel(Task task) {
        // loopCounter 是顺序多实例“子执行”上的本地变量，任务直接绑定在该子执行上，
        // 用非 Local 的 getVariable 从任务所在执行开始查找即可读到；getVariableLocal
        // 查的是“任务”自身的变量作用域，读不到 loopCounter。
        Integer loopCounter = (Integer) taskService.getVariable(task.getId(), "loopCounter");
        return loopCounter == null ? 1 : loopCounter + 1;
    }
}
