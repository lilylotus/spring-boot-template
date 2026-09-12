package com.example.template.approval.engine.flowable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.example.template.approval.api.dto.ApprovalActCommand;
import com.example.template.approval.api.dto.ApprovalAction;
import com.example.template.approval.api.dto.ApprovalDetailView;
import com.example.template.approval.api.dto.ApprovalNodeStatus;
import com.example.template.approval.api.dto.ApprovalInstanceView;
import com.example.template.approval.api.dto.ApprovalStatus;
import com.example.template.approval.api.dto.StartApprovalCommand;
import com.example.template.approval.config.entity.ApprovalChainConfig;
import com.example.template.approval.config.mapper.ApprovalChainConfigMapper;
import com.example.template.approval.link.service.ApprovalBizLinkService;
import com.example.template.approval.link.entity.ApprovalBizLink;
import com.example.template.approval.link.mapper.ApprovalBizLinkMapper;
import com.example.template.approval.record.entity.ApprovalActionRecord;
import com.example.template.approval.record.mapper.ApprovalActionRecordMapper;
import com.example.template.approval.record.service.ApprovalActionRecordService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FlowableApprovalGateway} 集成测试，覆盖三级顺序多实例的全部同意、首级驳回和中间驳回，
 * 并约束关键多实例变量、审批记录、越权保护及事务回滚行为。
 * <p>
 * 测试使用真实本地 MySQL 和 Flowable 引擎，不使用测试事务回滚；测试结束后清理本次审批配置和记录。
 */
@SpringBootTest
class FlowableApprovalGatewayIntegrationTest {

    @Autowired
    private FlowableApprovalGateway gateway;

    @Autowired
    private ApprovalChainConfigMapper approvalChainConfigMapper;

    @Autowired
    private ApprovalBizLinkService approvalBizLinkService;

    @Autowired
    private ApprovalBizLinkMapper approvalBizLinkMapper;

    @Autowired
    private ApprovalActionRecordService approvalActionRecordService;

    @Autowired
    private ApprovalActionRecordMapper approvalActionRecordMapper;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ApplicationContext applicationContext;

    private String bizType;

    @BeforeEach
    void ensureSpringContextHolderInitialized() {
        new SpringContextHolder().setApplicationContext(applicationContext);
    }

    @AfterEach
    void cleanup() {
        if (bizType != null) {
            approvalActionRecordMapper.delete(
                    Wrappers.<ApprovalActionRecord>lambdaQuery().eq(ApprovalActionRecord::getBizType, bizType));
            approvalChainConfigMapper.delete(
                    Wrappers.<ApprovalChainConfig>lambdaQuery().eq(ApprovalChainConfig::getBizType, bizType));
            approvalBizLinkMapper.delete(
                    Wrappers.<ApprovalBizLink>lambdaQuery().eq(ApprovalBizLink::getBizType, bizType));
        }
    }

    @Test
    void threeLevelChain_allLevelsAgree_completesEveryInstanceAndApproves() {
        bizType = "IT_OK_" + randomSuffix();
        saveChain(bizType, List.of("alice", "bob", "carol"));
        String bizId = "biz-" + UUID.randomUUID();

        ApprovalInstanceView started = gateway.start(new StartApprovalCommand(bizType, bizId, "operator", "{}"));
        String processInstanceId = findProcessInstanceId(bizId);
        ApprovalBizLink link = approvalBizLinkService.findLatestByBizKey(bizType, bizId).orElseThrow();
        approvalChainConfigMapper.delete(
                Wrappers.<ApprovalChainConfig>lambdaQuery().eq(ApprovalChainConfig::getBizType, bizType));
        saveChain(bizType, List.of("mallory"));
        assertThat(started.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(started.currentLevel()).isEqualTo(1);
        assertThat(started.currentApproverUserId()).isEqualTo("alice");
        assertCurrentTaskState(processInstanceId, "alice", 0, 0, null);
        assertThat(link.getInitiatorUserId()).isEqualTo("operator");
        assertThat(link.getApproverSnapshot()).isEqualTo("[\"alice\",\"bob\",\"carol\"]");
        assertThat(link.getPayloadSnapshot()).isEqualTo("{}");
        assertThat(gateway.listPendingTasks("alice"))
                .filteredOn(task -> task.bizId().equals(bizId))
                .singleElement()
                .satisfies(task -> assertThat(task.approvalId()).isEqualTo(link.getId()));

        gateway.act(new ApprovalActCommand(bizType, bizId, "alice", ApprovalAction.AGREE, "一级同意"));

        ApprovalInstanceView afterLevel1 = gateway.queryByBizKey(bizType, bizId);
        assertThat(afterLevel1.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(afterLevel1.currentLevel()).isEqualTo(2);
        assertThat(afterLevel1.currentApproverUserId()).isEqualTo("bob");
        assertCurrentTaskState(processInstanceId, "bob", 1, 1, "AGREE");
        assertApprovalRecord("alice", bizId, 1, ApprovalAction.AGREE, "一级同意");
        ApprovalDetailView pendingDetail = gateway.getApprovalDetail(link.getId(), "bob");
        assertThat(pendingDetail.currentLevel()).isEqualTo(2);
        assertThat(pendingDetail.initiatorUserId()).isEqualTo("operator");
        assertThat(pendingDetail.submittedTime()).isNotNull();
        assertThat(pendingDetail.payloadSnapshot()).isEqualTo("{}");
        assertThat(pendingDetail.payloadAvailable()).isTrue();
        assertThat(pendingDetail.nodes()).extracting(node -> node.approverUserId())
                .containsExactly("alice", "bob", "carol");
        assertThat(pendingDetail.nodes()).extracting(node -> node.status())
                .containsExactly(ApprovalNodeStatus.APPROVED, ApprovalNodeStatus.PENDING, ApprovalNodeStatus.WAITING);
        assertThat(pendingDetail.nodes().get(0).action()).isEqualTo(ApprovalAction.AGREE);
        assertThat(pendingDetail.nodes().get(0).comment()).isEqualTo("一级同意");
        assertThat(pendingDetail.nodes().get(0).actedTime()).isNotNull();
        assertThat(pendingDetail.nodes().get(1).taskCreatedTime()).isNotNull();
        assertThat(pendingDetail.nodes().get(2).taskCreatedTime()).isNull();
        assertThat(gateway.getApprovalDetail(link.getId(), "alice").approvalId()).isEqualTo(link.getId());
        assertThatThrownBy(() -> gateway.getApprovalDetail(link.getId(), "mallory"))
                .hasMessage("无权查看该审批详情");

        gateway.act(new ApprovalActCommand(bizType, bizId, "bob", ApprovalAction.AGREE, "二级同意"));

        ApprovalInstanceView afterLevel2 = gateway.queryByBizKey(bizType, bizId);
        assertThat(afterLevel2.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(afterLevel2.currentLevel()).isEqualTo(3);
        assertThat(afterLevel2.currentApproverUserId()).isEqualTo("carol");
        assertCurrentTaskState(processInstanceId, "carol", 2, 2, "AGREE");
        assertApprovalRecord("bob", bizId, 2, ApprovalAction.AGREE, "二级同意");

        gateway.act(new ApprovalActCommand(bizType, bizId, "carol", ApprovalAction.AGREE, "三级同意"));

        assertThat(taskService.createTaskQuery().processInstanceId(processInstanceId).list()).isEmpty();
        assertThat(gateway.queryByBizKey(bizType, bizId).status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approvalBizLinkService.findLatestByBizKey(bizType, bizId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.APPROVED.name());
        assertHistoricMultiInstanceOutcome(processInstanceId, "AGREE", 2, 3);
        assertApprovalRecord("carol", bizId, 3, ApprovalAction.AGREE, "三级同意");
        ApprovalDetailView approvedDetail = gateway.getApprovalDetail(link.getId(), "alice");
        assertThat(approvedDetail.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approvedDetail.currentLevel()).isNull();
        assertThat(approvedDetail.nodes()).extracting(node -> node.status())
                .containsExactly(ApprovalNodeStatus.APPROVED, ApprovalNodeStatus.APPROVED, ApprovalNodeStatus.APPROVED);

        approvalActionRecordMapper.delete(
                Wrappers.<ApprovalActionRecord>lambdaQuery()
                        .eq(ApprovalActionRecord::getBizType, bizType)
                        .eq(ApprovalActionRecord::getApproverUserId, "bob"));
        assertThat(gateway.getApprovalDetail(link.getId(), "alice").nodes()).extracting(node -> node.status())
                .containsExactly(ApprovalNodeStatus.APPROVED, ApprovalNodeStatus.WAITING, ApprovalNodeStatus.APPROVED);
    }

    @Test
    void threeLevelChain_firstLevelReject_completesOnlyFirstInstanceAndRejects() {
        bizType = "IT_FIRST_NO_" + randomSuffix();
        saveChain(bizType, List.of("alice", "bob", "carol"));
        String bizId = "biz-" + UUID.randomUUID();

        gateway.start(new StartApprovalCommand(bizType, bizId, "operator", "{}"));
        String processInstanceId = findProcessInstanceId(bizId);
        assertCurrentTaskState(processInstanceId, "alice", 0, 0, null);

        gateway.act(new ApprovalActCommand(bizType, bizId, "alice", ApprovalAction.REJECT, "一级驳回"));

        assertRejectedAndNoActiveTasks(processInstanceId, bizId);
        assertHistoricMultiInstanceOutcome(processInstanceId, "REJECT", 0, 1);
        assertHistoricAssigneeCount(processInstanceId, "bob", 0);
        assertHistoricAssigneeCount(processInstanceId, "carol", 0);
        assertApprovalRecord("alice", bizId, 1, ApprovalAction.REJECT, "一级驳回");
        assertNoApprovalRecord("bob", bizId);
        assertNoApprovalRecord("carol", bizId);
        ApprovalDetailView detail = gateway.getApprovalDetail(
                approvalBizLinkService.findLatestByBizKey(bizType, bizId).orElseThrow().getId(), "alice");
        assertThat(detail.nodes()).extracting(node -> node.status())
                .containsExactly(ApprovalNodeStatus.REJECTED, ApprovalNodeStatus.SKIPPED, ApprovalNodeStatus.SKIPPED);
    }

    @Test
    void threeLevelChain_middleLevelReject_completesTwoInstancesWithoutCreatingThird() {
        bizType = "IT_MIDDLE_NO_" + randomSuffix();
        saveChain(bizType, List.of("alice", "bob", "carol"));
        String bizId = "biz-" + UUID.randomUUID();

        gateway.start(new StartApprovalCommand(bizType, bizId, "operator", "{}"));
        String processInstanceId = findProcessInstanceId(bizId);
        assertCurrentTaskState(processInstanceId, "alice", 0, 0, null);

        gateway.act(new ApprovalActCommand(bizType, bizId, "alice", ApprovalAction.AGREE, "一级同意"));
        assertCurrentTaskState(processInstanceId, "bob", 1, 1, "AGREE");

        gateway.act(new ApprovalActCommand(bizType, bizId, "bob", ApprovalAction.REJECT, "二级驳回"));

        assertRejectedAndNoActiveTasks(processInstanceId, bizId);
        assertHistoricMultiInstanceOutcome(processInstanceId, "REJECT", 1, 2);
        assertHistoricAssigneeCount(processInstanceId, "carol", 0);
        assertApprovalRecord("alice", bizId, 1, ApprovalAction.AGREE, "一级同意");
        assertApprovalRecord("bob", bizId, 2, ApprovalAction.REJECT, "二级驳回");
        assertNoApprovalRecord("carol", bizId);
        ApprovalDetailView detail = gateway.getApprovalDetail(
                approvalBizLinkService.findLatestByBizKey(bizType, bizId).orElseThrow().getId(), "alice");
        assertThat(detail.nodes()).extracting(node -> node.status())
                .containsExactly(ApprovalNodeStatus.APPROVED, ApprovalNodeStatus.REJECTED, ApprovalNodeStatus.SKIPPED);
    }

    @Test
    void legacyLinkWithoutSnapshots_recoversDetailFromFlowableHistory() {
        bizType = "IT_LEGACY_" + randomSuffix();
        saveChain(bizType, List.of("alice"));
        String bizId = "biz-" + UUID.randomUUID();
        gateway.start(new StartApprovalCommand(bizType, bizId, "legacy-initiator", "{\"legacy\":true}"));
        ApprovalBizLink link = approvalBizLinkService.findLatestByBizKey(bizType, bizId).orElseThrow();
        gateway.act(new ApprovalActCommand(bizType, bizId, "alice", ApprovalAction.AGREE, "历史同意"));

        approvalBizLinkMapper.update(null,
                Wrappers.<ApprovalBizLink>lambdaUpdate()
                        .eq(ApprovalBizLink::getId, link.getId())
                        .set(ApprovalBizLink::getInitiatorUserId, null)
                        .set(ApprovalBizLink::getApproverSnapshot, null)
                        .set(ApprovalBizLink::getPayloadSnapshot, null));

        ApprovalDetailView detail = gateway.getApprovalDetail(link.getId(), "alice");
        assertThat(detail.initiatorUserId()).isEqualTo("legacy-initiator");
        assertThat(detail.payloadSnapshot()).isEqualTo("{\"legacy\":true}");
        assertThat(detail.payloadAvailable()).isTrue();
        assertThat(detail.nodes()).singleElement().satisfies(node -> {
            assertThat(node.approverUserId()).isEqualTo("alice");
            assertThat(node.status()).isEqualTo(ApprovalNodeStatus.APPROVED);
        });
    }

    @Test
    void legacyLinkWithoutFlowableVariables_marksPayloadUnavailable() {
        bizType = "IT_UNAVAILABLE_" + randomSuffix();
        ApprovalBizLink link = new ApprovalBizLink();
        link.setBizType(bizType);
        link.setBizId("biz-" + UUID.randomUUID());
        link.setProcessInstanceId("missing-process-" + UUID.randomUUID());
        link.setStatus(ApprovalStatus.APPROVED.name());
        approvalBizLinkService.save(link);

        ApprovalActionRecord record = new ApprovalActionRecord();
        record.setBizType(link.getBizType());
        record.setBizId(link.getBizId());
        record.setProcessInstanceId(link.getProcessInstanceId());
        record.setTaskId("missing-task-" + UUID.randomUUID());
        record.setApproverUserId("alice");
        record.setLevelNo(1);
        record.setTaskTitle("审批");
        record.setAction(ApprovalAction.AGREE.name());
        record.setTaskCreatedTime(LocalDateTime.now());
        approvalActionRecordService.save(record);

        ApprovalDetailView detail = gateway.getApprovalDetail(link.getId(), "alice");
        assertThat(detail.payloadAvailable()).isFalse();
        assertThat(detail.payloadSnapshot()).isNull();
        assertThat(detail.initiatorUserId()).isNull();
        assertThat(detail.nodes()).singleElement().satisfies(node -> {
            assertThat(node.approverUserId()).isEqualTo("alice");
            assertThat(node.status()).isEqualTo(ApprovalNodeStatus.APPROVED);
        });
    }

    @Test
    void actByNonApprover_doesNotCreateRecord() {
        bizType = "IT_AUTH_" + randomSuffix();
        saveChain(bizType, List.of("alice"));
        String bizId = "biz-" + UUID.randomUUID();
        gateway.start(new StartApprovalCommand(bizType, bizId, "operator", "{}"));

        assertThatThrownBy(() -> gateway.act(
                new ApprovalActCommand(bizType, bizId, "mallory", ApprovalAction.AGREE, "越权操作")))
                .hasMessageContaining("当前审批人无待处理任务");

        assertNoApprovalRecord("mallory", bizId);
        assertThat(gateway.listPendingTasks("alice"))
                .extracting(task -> task.bizId())
                .contains(bizId);
    }

    @Test
    void flowableCompletionFailure_rollsBackInsertedRecordAndLeavesTaskPending() {
        bizType = "IT_ROLLBACK_" + randomSuffix();
        saveChain(bizType, List.of("alice"));
        String bizId = "biz-" + UUID.randomUUID();
        gateway.start(new StartApprovalCommand(bizType, bizId, "operator", "{}"));

        try {
            new SpringContextHolder().setApplicationContext(null);
            assertThatThrownBy(() -> gateway.act(
                    new ApprovalActCommand(bizType, bizId, "alice", ApprovalAction.AGREE, "触发回滚")))
                    .hasMessageContaining("ApplicationContext 尚未初始化");
        } finally {
            new SpringContextHolder().setApplicationContext(applicationContext);
        }

        assertNoApprovalRecord("alice", bizId);
        assertThat(gateway.listPendingTasks("alice"))
                .extracting(task -> task.bizId())
                .contains(bizId);
    }

    private void assertCurrentTaskState(
            String processInstanceId,
            String expectedAssignee,
            int expectedLoopCounter,
            int expectedCompletedInstances,
            String expectedAction) {
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getAssignee()).isEqualTo(expectedAssignee);
        assertThat(taskService.getVariable(task.getId(), "loopCounter")).isEqualTo(expectedLoopCounter);
        assertThat(taskService.getVariable(task.getId(), "nrOfCompletedInstances"))
                .isEqualTo(expectedCompletedInstances);
        assertThat(taskService.getVariable(task.getId(), "approvalAction")).isEqualTo(expectedAction);
    }

    private void assertHistoricMultiInstanceOutcome(
            String processInstanceId,
            String expectedAction,
            int expectedLastLoopCounter,
            long expectedCompletedInstances) {
        assertThat(historicVariableValue(processInstanceId, "approvalAction")).isEqualTo(expectedAction);

        Object historicLoopCounter = historicVariableValue(processInstanceId, "loopCounter");
        if (historicLoopCounter != null) {
            assertThat(historicLoopCounter).isEqualTo(expectedLastLoopCounter);
        }

        Object historicCompletedInstances = historicVariableValue(processInstanceId, "nrOfCompletedInstances");
        if (historicCompletedInstances != null) {
            assertThat(historicCompletedInstances).isEqualTo((int) expectedCompletedInstances);
        }

        assertThat(historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .finished()
                .count()).isEqualTo(expectedCompletedInstances);
    }

    private Object historicVariableValue(String processInstanceId, String variableName) {
        HistoricVariableInstance variable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .variableName(variableName)
                .singleResult();
        return variable == null ? null : variable.getValue();
    }

    private void assertHistoricAssigneeCount(String processInstanceId, String assignee, long expectedCount) {
        assertThat(historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskAssignee(assignee)
                .count()).isEqualTo(expectedCount);
    }

    private void assertRejectedAndNoActiveTasks(String processInstanceId, String bizId) {
        assertThat(taskService.createTaskQuery().processInstanceId(processInstanceId).list()).isEmpty();
        assertThat(gateway.queryByBizKey(bizType, bizId).status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(approvalBizLinkService.findLatestByBizKey(bizType, bizId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.REJECTED.name());
    }

    private void assertApprovalRecord(
            String approverUserId,
            String bizId,
            int level,
            ApprovalAction action,
            String comment) {
        assertThat(gateway.listApprovalRecords(approverUserId))
                .filteredOn(record -> record.bizId().equals(bizId))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.approvalId()).isEqualTo(
                            approvalBizLinkService.findLatestByBizKey(bizType, bizId).orElseThrow().getId());
                    assertThat(record.bizType()).isEqualTo(bizType);
                    assertThat(record.level()).isEqualTo(level);
                    assertThat(record.taskTitle()).isEqualTo("审批");
                    assertThat(record.action()).isEqualTo(action);
                    assertThat(record.comment()).isEqualTo(comment);
                    assertThat(record.taskCreatedTime()).isNotNull();
                    assertThat(record.actedTime()).isNotNull();
                });
    }

    private void assertNoApprovalRecord(String approverUserId, String bizId) {
        assertThat(approvalActionRecordService.listByApproverUserId(approverUserId))
                .noneMatch(record -> record.getBizId().equals(bizId));
    }

    private String findProcessInstanceId(String bizId) {
        return approvalBizLinkService.findLatestByBizKey(bizType, bizId)
                .orElseThrow()
                .getProcessInstanceId();
    }

    private String randomSuffix() {
        // biz_type 列为 varchar(32)，只取 UUID 前 8 位保证唯一性且不超长。
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private void saveChain(String bizType, List<String> approvers) {
        LocalDateTime now = LocalDateTime.now();
        int level = 1;
        for (String approver : approvers) {
            ApprovalChainConfig entity = new ApprovalChainConfig();
            entity.setBizType(bizType);
            entity.setLevelNo(level++);
            entity.setApproverUserId(approver);
            entity.setCreatedTime(now);
            entity.setUpdatedTime(now);
            approvalChainConfigMapper.insert(entity);
        }
    }
}
