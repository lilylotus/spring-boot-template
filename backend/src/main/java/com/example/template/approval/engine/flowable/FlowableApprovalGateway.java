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
import com.example.template.usergroup.service.UserGroupService;
import com.example.template.approval.config.design.GroupAssignment;
import com.example.template.approval.link.mapper.ApprovalBizLinkMapper;
import com.example.template.approval.api.dto.ApprovalMemberActionView;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.transaction.annotation.Isolation;

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
    private final UserGroupService userGroupService;
    private final ApprovalBizLinkMapper linkMapper;

    /** 发起审批并保存模板与指派引用；组成员在每次操作时实时读取。 */
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
                GroupAssignment.isGroup(approverList.get(0)) ? null : approverList.get(0));
    }

    /** 通过业务键查询当前节点和最终结果，不依赖用户业务表结构。 */
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

    /** 在组锁和实例锁内重新授权并记录动作，只有节点最终结算才推进流程。 */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void act(ApprovalActCommand command) {
        ApprovalBizLink link = approvalBizLinkService.findLatestByBizKey(command.bizType(), command.bizId())
                .filter(l -> ApprovalStatus.PENDING.name().equals(l.getStatus()))
                .orElseThrow(() -> new BusinessException(
                        "未找到进行中的审批实例：" + command.bizType() + ":" + command.bizId()));

        Task initialTask = findActiveTask(link.getProcessInstanceId());
        if (initialTask == null) {
            throw new BusinessException("当前审批人无待处理任务，或该任务已被处理");
        }
        String assignment = assignment(initialTask);
        Long groupId = GroupAssignment.isGroup(assignment) ? GroupAssignment.id(assignment) : null;
        // 先锁组再锁实例；成员维护使用同一组锁，防止过期成员获得结算权限。
        if (groupId != null) {
            userGroupService.lock(groupId);
        }
        linkMapper.selectOne(Wrappers.<ApprovalBizLink>lambdaQuery()
                .eq(ApprovalBizLink::getId, link.getId()).last("FOR UPDATE"));
        Task task = findActiveTask(link.getProcessInstanceId());
        if (task == null || !task.getId().equals(initialTask.getId())) {
            throw new BusinessException("当前审批人无待处理任务，或该任务已被处理");
        }
        List<String> members = groupId == null ? List.of() : userGroupService.activeMembers(groupId);
        if (groupId == null ? !command.approverUserId().equals(task.getAssignee())
                : !members.contains(command.approverUserId())) {
            throw new BusinessException("当前审批人无待处理任务，或该任务已被处理");
        }
        List<ApprovalActionRecord> votes = taskVotes(task);
        boolean voted = votes.stream().anyMatch(vote -> command.approverUserId().equals(vote.getApproverUserId()));
        boolean allRejected = groupId != null && allRejected(members, votes);
        if (voted && !allRejected) {
            throw new BusinessException("当前成员已经处理该节点");
        }
        if (voted) {
            // 移出成员后可显式确认全员驳回结果；不能将旧投票改写为同意。
            if (command.action() != ApprovalAction.REJECT) {
                throw new BusinessException("当前成员已驳回，请确认驳回结果");
            }
            complete(task, ApprovalAction.REJECT, command.comment());
            return;
        }

        ApprovalActionRecord record = new ApprovalActionRecord();
        record.setBizType(link.getBizType());
        record.setBizId(link.getBizId());
        record.setProcessInstanceId(link.getProcessInstanceId());
        record.setTaskId(task.getId());
        record.setApproverUserId(command.approverUserId());
        record.setGroupId(groupId);
        if (groupId != null) {
            record.setGroupName(userGroupService.get(groupId).name());
        }
        record.setLevelNo(resolveLevel(task));
        record.setTaskTitle(groupId == null ? task.getName() : "用户组：" + userGroupService.get(groupId).name());
        record.setAction(command.action().name());
        record.setComment(command.comment());
        record.setTaskCreatedTime(toLocalDateTime(task));
        approvalActionRecordService.save(record);

        if (groupId != null && command.action() == ApprovalAction.REJECT) {
            List<ApprovalActionRecord> currentVotes = new ArrayList<>(votes);
            currentVotes.add(record);
            if (!allRejected(members, currentVotes)) {
                return;
            }
        }
        complete(task, command.action(), command.comment());
    }

    /** 查询个人待办及最新有效组成员可参与的共享待办。 */
    @Override
    public List<ApprovalTaskView> listPendingTasks(String approverUserId) {
        List<String> candidateGroups = userGroupService.list().stream()
                .filter(group -> group.activeMemberIds().contains(approverUserId))
                .map(group -> GroupAssignment.encode(group.id())).toList();
        List<Task> tasks = taskService.createTaskQuery()
                .or().taskAssignee(approverUserId)
                .taskCandidateGroupIn(candidateGroups.isEmpty() ? List.of("group:none") : candidateGroups)
                .endOr()
                .orderByTaskCreateTime().asc()
                .list();

        List<ApprovalTaskView> result = new ArrayList<>();
        for (Task task : tasks) {
            ApprovalBizLink link = approvalBizLinkService.findByProcessInstanceId(task.getProcessInstanceId())
                    .orElse(null);
            if (link == null || !canProcess(task, approverUserId)) {
                continue;
            }
            LocalDateTime createdTime = toLocalDateTime(task);
            String target = assignment(task);
            Long groupId = GroupAssignment.isGroup(target) ? GroupAssignment.id(target) : null;
            result.add(new ApprovalTaskView(link.getId(), link.getBizType(), link.getBizId(), resolveLevel(task),
                    taskTitle(task), createdTime, groupId, groupId == null ? null : userGroupService.get(groupId).name(),
                    groupId != null && allRejected(userGroupService.activeMembers(groupId), taskVotes(task))));
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

    /** 将真实动作映射为审批记录，并关联稳定的审批实例ID。 */
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

    /** 查询当前顺序节点的唯一活动任务。 */
    private Task findActiveTask(String processInstanceId) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
    }

    /** 根据最新候选资格或已有真实动作校验详情访问权限。 */
    private void validateDetailAccess(
            String viewerUserId,
            Task activeTask,
            List<ApprovalActionRecord> actionRecords) {
        boolean isCurrentApprover = activeTask != null && isCandidate(activeTask, viewerUserId);
        boolean hasProcessed = actionRecords.stream()
                .anyMatch(record -> viewerUserId.equals(record.getApproverUserId()));
        if (!isCurrentApprover && !hasProcessed) {
            throw new BusinessException("无权查看该审批详情");
        }
    }

    /** 读取实例中的有序指派引用，兼容旧个人审批快照。 */
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

    /** 读取当次业务数据快照，历史缺失时从引擎变量恢复。 */
    private String resolvePayloadSnapshot(ApprovalBizLink link) {
        if (link.getPayloadSnapshot() != null) {
            return link.getPayloadSnapshot();
        }
        Object legacyPayload = resolveFlowableVariable(link.getProcessInstanceId(), "payloadSnapshot");
        return toNullableString(legacyPayload);
    }

    /** 按运行时、历史变量顺序恢复审批信息。 */
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

    /** 将历史变量转为可空JSON文本。 */
    private String toNullableString(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof String stringValue ? stringValue : JacksonUtils.toJson(value);
    }

    /** 按实例有序指派构建个人和组节点，分别聚合最终状态与投票。 */
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

        Integer rejectedLevel = approvalStatus == ApprovalStatus.REJECTED ? actionRecords.stream()
                .filter(record -> ApprovalAction.REJECT.name().equals(record.getAction()))
                .mapToInt(ApprovalActionRecord::getLevelNo).max().stream().boxed().findFirst().orElse(null) : null;
        List<ApprovalNodeView> nodes = new ArrayList<>();
        for (int level = 1; level <= nodeCount; level++) {
            if (level <= approvers.size() && GroupAssignment.isGroup(approvers.get(level - 1))) {
                nodes.add(groupNode(level, approvers.get(level - 1), actionRecords, activeTask,
                        approvalStatus, currentLevel, rejectedLevel));
                continue;
            }
            ApprovalActionRecord record = recordByLevel.get(level);
            boolean isCurrent = currentLevel != null && currentLevel == level;
            String approverUserId = resolveNodeApprover(approvers, record, activeTask, level, isCurrent);
            ApprovalNodeStatus nodeStatus = resolveNodeStatus(record, approvalStatus, rejectedLevel, level, isCurrent);
            nodes.add(toApprovalNodeView(level, approverUserId, nodeStatus, record, activeTask, isCurrent));
        }
        return nodes;
    }

    /** 从实例快照、动作或当前任务恢复个人节点审批人。 */
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

    /** 根据个人动作与整体终态计算节点状态。 */
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

    /** 将个人节点动作和时间映射为兼容视图。 */
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


    /** 返回任务绑定的组引用或个人ID；组引用只含组ID，不含成员集合。 */
    private String assignment(Task task) {
        Object value = taskService.getVariable(task.getId(), "currentApprover");
        return value instanceof String text ? text : task.getAssignee();
    }

    /** 查询本次共享任务的实际投票，不能将其他节点或旧实例的投票混入。 */
    private List<ApprovalActionRecord> taskVotes(Task task) {
        return approvalActionRecordService.listByProcessInstanceId(task.getProcessInstanceId()).stream()
                .filter(record -> task.getId().equals(record.getTaskId())).toList();
    }

    /** 判断当前非空有效成员集合是否全部已驳回；空组始终保持待处理。 */
    private boolean allRejected(List<String> members, List<ApprovalActionRecord> votes) {
        return !members.isEmpty() && members.stream().allMatch(member -> votes.stream()
                .anyMatch(vote -> member.equals(vote.getApproverUserId()) && "REJECT".equals(vote.getAction())));
    }

    /** 实时判断候选资格，移出组的用户不能通过缓存页面或旧待办继续访问。 */
    private boolean isCandidate(Task task, String userId) {
        String target = assignment(task);
        return GroupAssignment.isGroup(target)
                ? userGroupService.activeMembers(GroupAssignment.id(target)).contains(userId)
                : userId.equals(task.getAssignee());
    }

    /** 已投票成员隐藏普通待办；成员变化导致全员驳回时允许显式确认结果。 */
    private boolean canProcess(Task task, String userId) {
        if (!isCandidate(task, userId)) {
            return false;
        }
        String target = assignment(task);
        if (!GroupAssignment.isGroup(target)) {
            return true;
        }
        List<ApprovalActionRecord> votes = taskVotes(task);
        return votes.stream().noneMatch(vote -> userId.equals(vote.getApproverUserId()))
                || allRejected(userGroupService.activeMembers(GroupAssignment.id(target)), votes);
    }

    /** 为共享待办展示组名称和必要的结果确认提示。 */
    private String taskTitle(Task task) {
        String target = assignment(task);
        if (!GroupAssignment.isGroup(target)) {
            return task.getName();
        }
        var group = userGroupService.get(GroupAssignment.id(target));
        return "用户组：" + group.name()
                + (allRejected(group.activeMemberIds(), taskVotes(task)) ? "（待确认驳回结果）" : "（任一成员同意）");
    }

    /** 只有节点最终结算才完成引擎任务；部分成员驳回不写终止变量。 */
    private void complete(Task task, ApprovalAction action, String comment) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("approvalAction", action.name());
        variables.put("approvalComment", comment);
        taskService.complete(task.getId(), variables);
    }

    /** 聚合组节点结果与实际动作；当前成员只用于展示，不重新计算已完成结果。 */
    private ApprovalNodeView groupNode(int level, String target, List<ApprovalActionRecord> records,
                                       Task activeTask, ApprovalStatus status, Integer currentLevel,
                                       Integer rejectedLevel) {
        Long groupId = GroupAssignment.id(target);
        var group = userGroupService.get(groupId);
        List<ApprovalActionRecord> votes = records.stream()
                .filter(record -> record.getLevelNo() == level).toList();
        boolean isCurrent = currentLevel != null && currentLevel == level;
        boolean passed = votes.stream().anyMatch(record -> "AGREE".equals(record.getAction()));
        ApprovalNodeStatus nodeStatus = passed ? ApprovalNodeStatus.APPROVED
                : isCurrent ? ApprovalNodeStatus.PENDING
                : status == ApprovalStatus.REJECTED && rejectedLevel != null && level == rejectedLevel
                ? ApprovalNodeStatus.REJECTED
                : status == ApprovalStatus.REJECTED && rejectedLevel != null && level > rejectedLevel
                ? ApprovalNodeStatus.SKIPPED : ApprovalNodeStatus.WAITING;
        ApprovalActionRecord decisive = votes.stream().filter(record -> "AGREE".equals(record.getAction()))
                .findFirst().orElse(votes.isEmpty() ? null : votes.get(votes.size() - 1));
        return new ApprovalNodeView(level, null, nodeStatus,
                group.name() + (isCurrent && group.activeMemberIds().isEmpty() ? "（停用或无有效成员）" : ""),
                nodeStatus == ApprovalNodeStatus.APPROVED ? ApprovalAction.AGREE
                        : nodeStatus == ApprovalNodeStatus.REJECTED ? ApprovalAction.REJECT : null,
                decisive == null ? null : decisive.getComment(),
                isCurrent && activeTask != null ? toLocalDateTime(activeTask)
                        : decisive == null ? null : decisive.getTaskCreatedTime(),
                nodeStatus == ApprovalNodeStatus.PENDING || decisive == null ? null : decisive.getActedTime(),
                groupId, group.name(), group.activeMemberIds(),
                votes.stream().map(record -> new ApprovalMemberActionView(record.getApproverUserId(),
                        record.getAction(), record.getComment(), record.getActedTime(), record.getGroupName())).toList());
    }

    /** 将引擎任务到达时间转换为本地时间。 */
    private LocalDateTime toLocalDateTime(Task task) {
        return task.getCreateTime() == null ? null
                : LocalDateTime.ofInstant(task.getCreateTime().toInstant(), ZoneId.systemDefault());
    }

    /** 从顺序多实例局部计数器计算逻辑审批级别。 */
    private int resolveLevel(Task task) {
        // loopCounter 是顺序多实例“子执行”上的本地变量，任务直接绑定在该子执行上，
        // 用非 Local 的 getVariable 从任务所在执行开始查找即可读到；getVariableLocal
        // 查的是“任务”自身的变量作用域，读不到 loopCounter。
        Integer loopCounter = (Integer) taskService.getVariable(task.getId(), "loopCounter");
        return loopCounter == null ? 1 : loopCounter + 1;
    }
}
