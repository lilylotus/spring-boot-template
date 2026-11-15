package com.example.template.approval.engine.flowable;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.flowable.engine.RuntimeService;
import com.example.template.approval.config.entity.ApprovalChainConfig;
import com.example.template.approval.config.entity.ApprovalProcessTemplate;
import com.example.template.approval.config.entity.ApprovalProcessTemplateVersion;
import com.example.template.approval.config.mapper.ApprovalChainConfigMapper;
import com.example.template.approval.config.mapper.ApprovalProcessTemplateMapper;
import com.example.template.approval.config.mapper.ApprovalProcessTemplateVersionMapper;
import com.example.template.approval.link.entity.ApprovalBizLink;
import com.example.template.approval.link.mapper.ApprovalBizLinkMapper;
import com.example.template.approval.record.entity.ApprovalActionRecord;
import com.example.template.approval.record.mapper.ApprovalActionRecordMapper;
import com.example.template.usergroup.entity.UserGroupMember;
import com.example.template.usergroup.mapper.UserGroupMapper;
import com.example.template.usergroup.mapper.UserGroupMemberMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.*;
import com.example.template.approval.api.dto.*;
import com.example.template.approval.config.design.*;
import com.example.template.approval.config.dto.*;
import com.example.template.approval.config.service.ApprovalProcessTemplateService;
import com.example.template.approval.link.service.ApprovalBizLinkService;
import com.example.template.identity.entity.SysUser;
import com.example.template.identity.mapper.SysUserMapper;
import com.example.template.usergroup.service.UserGroupService;
import com.example.template.usergroup.dto.*;
import com.example.template.common.BusinessException;
import static org.assertj.core.api.Assertions.*;

/** 在真实引擎事务中验证实时组授权、共享投票和旧个人节点混合流转；每例回滚业务数据。 */
@SpringBootTest
@Transactional(isolation = Isolation.READ_COMMITTED)
class GroupApprovalIntegrationTest {
    @Autowired private FlowableApprovalGateway gateway;
    @Autowired private ApprovalProcessTemplateService templates;
    @Autowired private ApprovalBizLinkService links;
    @Autowired private UserGroupService groups;
    @Autowired private SysUserMapper users;
    @Autowired private ApplicationContext context;
    @Autowired private ApprovalBizLinkMapper linkMapper;
    @Autowired private ApprovalActionRecordMapper recordMapper;
    @Autowired private ApprovalChainConfigMapper chainMapper;
    @Autowired private ApprovalProcessTemplateMapper templateMapper;
    @Autowired private ApprovalProcessTemplateVersionMapper versionMapper;
    @Autowired private UserGroupMapper groupMapper;
    @Autowired private UserGroupMemberMapper groupMemberMapper;
    @Autowired private RuntimeService runtimeService;
    /** 标记本例夹具是否真实提交（并发用例不使用测试事务），为真时在用例结束后清理。 */
    private boolean committedFixture;
    private Long alice;
    private Long bob;
    private Long carol;
    private UserGroupView group;
    private String bizType;
    private String bizId;

    /** 建立隔离用户组和唯一业务类型，避免测试污染实际用户审批配置。 */
    @BeforeEach
    void setup() {
        new SpringContextHolder().setApplicationContext(context);
        alice = user("alice");
        bob = user("bob");
        carol = user("carol");
        group = groups.save(null, new UserGroupRequest("test-" + UUID.randomUUID(), "审核组", "",
                "ACTIVE", 0L, List.of(alice, bob)), "test");
        bizType = "GROUP_" + UUID.randomUUID().toString().substring(0, 8);
        bizId = UUID.randomUUID().toString();
    }

    /** 发起后换成员应改变待办，旧成员提交被拒，新成员同意只推进一个逻辑节点。 */
    @Test
    void membershipChange_runningInstance_usesLatestMembers() {
        publish(true);
        start();
        assertThat(pending(alice)).isTrue();
        assertThat(pending(bob)).isTrue();
        replace(List.of(bob, carol), "ACTIVE");
        assertThat(pending(alice)).isFalse();
        assertThat(pending(carol)).isTrue();
        assertThatThrownBy(() -> act(alice, ApprovalAction.AGREE)).isInstanceOf(BusinessException.class);
        act(carol, ApprovalAction.AGREE);
        assertThat(pending(bob)).isFalse();
        assertThat(gateway.queryByBizKey(bizType, bizId).currentLevel()).isEqualTo(2);
        // 第二级为个人节点；组节点通过不应跳过后续个人审批。
        act(alice, ApprovalAction.AGREE);
        assertThat(gateway.queryByBizKey(bizType, bizId).status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(detail(carol).nodes().get(0).memberActions()).singleElement()
                .satisfies(vote -> assertThat(vote.userId()).isEqualTo(carol.toString()));
    }

    /** 部分成员驳回后另一成员同意，节点最终通过且两条真实动作都保留。 */
    @Test
    void partialRejection_thenAgreement_preservesBothVotes() {
        publish(false);
        start();
        act(alice, ApprovalAction.REJECT);
        assertThat(pending(alice)).isFalse();
        assertThat(pending(bob)).isTrue();
        assertThat(detail(alice).nodes().get(0).status()).isEqualTo(ApprovalNodeStatus.PENDING);
        act(bob, ApprovalAction.AGREE);
        assertThat(detail(alice).nodes().get(0).status()).isEqualTo(ApprovalNodeStatus.APPROVED);
        assertThat(detail(alice).nodes().get(0).memberActions()).hasSize(2);
        replace(List.of(carol), "ACTIVE");
        assertThat(detail(alice).nodes().get(0).memberActions()).hasSize(2);
        assertThat(detail(alice).nodes().get(0).currentMemberIds()).containsExactly(carol.toString());
        assertThatThrownBy(() -> detail(carol)).hasMessage("无权查看该审批详情");
    }

    /** 全部当前成员驳回时终止流程并跳过后续个人节点。 */
    @Test
    void allMembersReject_stopsBeforeNextNode() {
        publish(true);
        start();
        act(alice, ApprovalAction.REJECT);
        act(bob, ApprovalAction.REJECT);
        assertThat(gateway.queryByBizKey(bizType, bizId).status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(detail(alice).nodes()).extracting(ApprovalNodeView::status)
                .containsExactly(ApprovalNodeStatus.REJECTED, ApprovalNodeStatus.SKIPPED);
    }

    /** 空组和停用组暂停进行中任务，恢复后新成员能够继续处理。 */
    @Test
    void emptyOrDisabledGroup_restorationResumesSameInstance() {
        publish(false);
        start();
        replace(List.of(), "ACTIVE");
        assertThat(pending(alice)).isFalse();
        assertThat(gateway.queryByBizKey(bizType, bizId).status()).isEqualTo(ApprovalStatus.PENDING);
        replace(List.of(carol), "DISABLED");
        assertThat(pending(carol)).isFalse();
        assertThatThrownBy(() -> act(carol, ApprovalAction.AGREE)).isInstanceOf(BusinessException.class);
        replace(List.of(carol), "ACTIVE");
        assertThat(pending(carol)).isTrue();
        act(carol, ApprovalAction.AGREE);
        assertThat(gateway.queryByBizKey(bizType, bizId).status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    /** 移出未投票成员后允许显式确认全员驳回，不插入重复投票。 */
    @Test
    void removalLeavesOnlyRejectedMembers_explicitConfirmationDoesNotDuplicateVote() {
        publish(false);
        start();
        act(alice, ApprovalAction.REJECT);
        replace(List.of(alice), "ACTIVE");
        assertThat(pending(alice)).isTrue();
        assertThat(gateway.queryByBizKey(bizType, bizId).status()).isEqualTo(ApprovalStatus.PENDING);
        act(alice, ApprovalAction.REJECT);
        assertThat(detail(alice).nodes().get(0).status()).isEqualTo(ApprovalNodeStatus.REJECTED);
        assertThat(detail(alice).nodes().get(0).memberActions()).hasSize(1);
    }

    /** 成员移出再加入不能重复投票，仍未处理的其他成员可以继续审批。 */
    @Test
    void rejoinedMember_cannotVoteTwice() {
        publish(false);
        start();
        act(alice, ApprovalAction.REJECT);
        replace(List.of(bob), "ACTIVE");
        replace(List.of(alice, bob), "ACTIVE");
        assertThatThrownBy(() -> act(alice, ApprovalAction.AGREE)).hasMessage("当前成员已经处理该节点");
        act(bob, ApprovalAction.AGREE);
    }

    /** 发布后的组被清空应拒绝新实例，避免生成无法处理的审批。 */
    @Test
    void emptyGroup_rejectsNewStart() {
        publish(false);
        replace(List.of(), "ACTIVE");
        assertThatThrownBy(this::start).isInstanceOf(BusinessException.class);
        assertThat(links.findLatestByBizKey(bizType, bizId)).isEmpty();
    }

    /** 草稿引用空组时不可发布。 */
    @Test
    void emptyGroup_rejectsPublish() {
        replace(List.of(), "ACTIVE");
        assertThatThrownBy(() -> publish(false)).isInstanceOf(BusinessException.class);
    }

    /** 任一成员同意即通过节点：其他成员待办消失且不能再处理，只保留一条真实动作。 */
    @Test
    void anyMemberAgreement_passesNodeAndRemovesOtherMembersTodo() {
        publish(false);
        start();
        assertThat(pending(alice)).isTrue();
        assertThat(pending(bob)).isTrue();
        act(alice, ApprovalAction.AGREE);
        assertThat(pending(alice)).isFalse();
        assertThat(pending(bob)).isFalse();
        assertThat(gateway.queryByBizKey(bizType, bizId).status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThatThrownBy(() -> act(bob, ApprovalAction.AGREE)).isInstanceOf(BusinessException.class);
        ApprovalNodeView node = detail(alice).nodes().get(0);
        assertThat(node.status()).isEqualTo(ApprovalNodeStatus.APPROVED);
        assertThat(node.memberActions()).singleElement()
                .satisfies(vote -> assertThat(vote.userId()).isEqualTo(alice.toString()));
        assertThat(gateway.listApprovalRecords(bob.toString())).noneMatch(record -> bizId.equals(record.bizId()));
    }

    /** 待办、审批记录和详情节点都应返回逻辑节点ID、指派类型及用户组名称，快照不含组成员。 */
    @Test
    void views_exposeNodeIdAssigneeTypeAndGroupName() {
        publish(true);
        start();
        assertThat(links.findLatestByBizKey(bizType, bizId).orElseThrow().getNodeSnapshot()).isEqualTo(
                "[{\"nodeId\":\"group\",\"assigneeType\":\"GROUP\",\"groupId\":" + group.id() + "},"
                        + "{\"nodeId\":\"personal\",\"assigneeType\":\"USER\",\"approverUserId\":\"" + alice + "\"}]");

        assertThat(task(bob)).satisfies(task -> {
            assertThat(task.nodeId()).isEqualTo("group");
            assertThat(task.assigneeType()).isEqualTo("GROUP");
            assertThat(task.groupId()).isEqualTo(group.id());
            assertThat(task.groupName()).isEqualTo("审核组");
        });
        act(bob, ApprovalAction.AGREE);
        assertThat(task(alice)).satisfies(task -> {
            assertThat(task.nodeId()).isEqualTo("personal");
            assertThat(task.assigneeType()).isEqualTo("USER");
            assertThat(task.groupId()).isNull();
            assertThat(task.groupName()).isNull();
        });
        act(alice, ApprovalAction.AGREE);

        // 组改名后，审批记录仍返回处理时的组名称。
        group = groups.save(group.id(), new UserGroupRequest(group.code(), "改名后的组", "",
                "ACTIVE", group.revision(), List.of(alice, bob)), "test");
        assertThat(record(bob)).satisfies(record -> {
            assertThat(record.nodeId()).isEqualTo("group");
            assertThat(record.assigneeType()).isEqualTo("GROUP");
            assertThat(record.groupId()).isEqualTo(group.id());
            assertThat(record.groupName()).isEqualTo("审核组");
        });
        assertThat(record(alice)).satisfies(record -> {
            assertThat(record.nodeId()).isEqualTo("personal");
            assertThat(record.assigneeType()).isEqualTo("USER");
            assertThat(record.groupId()).isNull();
            assertThat(record.groupName()).isNull();
        });

        List<ApprovalNodeView> nodes = detail(alice).nodes();
        assertThat(nodes).extracting(ApprovalNodeView::nodeId).containsExactly("group", "personal");
        assertThat(nodes).extracting(ApprovalNodeView::assigneeType).containsExactly("GROUP", "USER");
        assertThat(nodes.get(0).groupId()).isEqualTo(group.id());
        assertThat(nodes.get(0).groupName()).isEqualTo("改名后的组");
        assertThat(nodes.get(0).memberActions()).singleElement()
                .satisfies(vote -> assertThat(vote.groupName()).isEqualTo("审核组"));
        assertThat(nodes.get(1).groupName()).isNull();
    }

    /** 没有节点快照的历史实例：节点ID为空，指派类型按组信息推断，动作记录不写节点ID。 */
    @Test
    void legacyInstanceWithoutNodeSnapshot_returnsNullNodeIdAndInferredAssigneeType() {
        publish(true);
        start();
        Long approvalId = links.findLatestByBizKey(bizType, bizId).orElseThrow().getId();
        linkMapper.update(null, Wrappers.<ApprovalBizLink>lambdaUpdate()
                .eq(ApprovalBizLink::getId, approvalId).set(ApprovalBizLink::getNodeSnapshot, null));

        assertThat(task(bob)).satisfies(task -> {
            assertThat(task.nodeId()).isNull();
            assertThat(task.assigneeType()).isEqualTo("GROUP");
        });
        act(bob, ApprovalAction.AGREE);
        assertThat(task(alice)).satisfies(task -> {
            assertThat(task.nodeId()).isNull();
            assertThat(task.assigneeType()).isEqualTo("USER");
        });
        assertThat(record(bob)).satisfies(record -> {
            assertThat(record.nodeId()).isNull();
            assertThat(record.assigneeType()).isEqualTo("GROUP");
            assertThat(record.groupName()).isEqualTo("审核组");
        });
        List<ApprovalNodeView> nodes = detail(alice).nodes();
        assertThat(nodes).extracting(ApprovalNodeView::nodeId).containsOnlyNulls();
        assertThat(nodes).extracting(ApprovalNodeView::assigneeType).containsExactly("GROUP", "USER");
    }

    /**
     * 同组两个成员并发同意：只有一个成功推进节点，另一个得到业务异常，流程只前进一级且没有孤立动作记录。
     * 并发线程各自使用独立事务，因此本例不使用测试事务回滚，夹具真实提交并在用例结束后清理。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAgreement_onlyOneMemberAdvancesNode() throws Exception {
        committedFixture = true;
        publish(true);
        start();
        String processInstanceId = links.findLatestByBizKey(bizType, bizId).orElseThrow().getProcessInstanceId();

        List<Long> members = List.of(alice, bob);
        CountDownLatch ready = new CountDownLatch(members.size());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(members.size());
        Map<Long, Throwable> failures = new HashMap<>();
        try {
            Map<Long, Future<Throwable>> futures = new LinkedHashMap<>();
            for (Long member : members) {
                futures.put(member, pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        act(member, ApprovalAction.AGREE);
                        return null;
                    } catch (Throwable throwable) {
                        return throwable;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Map.Entry<Long, Future<Throwable>> entry : futures.entrySet()) {
                Throwable failure = entry.getValue().get(60, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.put(entry.getKey(), failure);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // 恰好一个成员失败，且失败是“已被处理”类业务异常而非引擎或数据库异常。
        assertThat(failures).hasSize(1);
        Long loser = failures.keySet().iterator().next();
        Long winner = loser.equals(alice) ? bob : alice;
        assertThat(failures.get(loser)).isInstanceOf(BusinessException.class);

        // 流程只前进一级，动作记录只有胜出成员的一条，没有孤立记录。
        ApprovalInstanceView afterRace = gateway.queryByBizKey(bizType, bizId);
        assertThat(afterRace.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(afterRace.currentLevel()).isEqualTo(2);
        assertThat(recordMapper.selectList(Wrappers.<ApprovalActionRecord>lambdaQuery()
                .eq(ApprovalActionRecord::getProcessInstanceId, processInstanceId)))
                .singleElement().satisfies(record -> {
                    assertThat(record.getApproverUserId()).isEqualTo(winner.toString());
                    assertThat(record.getLevelNo()).isEqualTo(1);
                    assertThat(record.getNodeId()).isEqualTo("group");
                    assertThat(record.getAction()).isEqualTo(ApprovalAction.AGREE.name());
                });
        assertThat(gateway.listApprovalRecords(loser.toString())).noneMatch(record -> bizId.equals(record.bizId()));

        // 后续个人节点仍可正常完成，说明并发冲突没有破坏流程状态。
        act(alice, ApprovalAction.AGREE);
        assertThat(gateway.queryByBizKey(bizType, bizId).status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    /** 清理并发用例真实提交的夹具；事务回滚的用例无需处理。 */
    @AfterEach
    void cleanupCommittedFixture() {
        if (!committedFixture) {
            return;
        }
        runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(bizType + ":" + bizId).list()
                .forEach(instance -> runtimeService.deleteProcessInstance(instance.getId(), "测试清理"));
        recordMapper.delete(Wrappers.<ApprovalActionRecord>lambdaQuery().eq(ApprovalActionRecord::getBizType, bizType));
        linkMapper.delete(Wrappers.<ApprovalBizLink>lambdaQuery().eq(ApprovalBizLink::getBizType, bizType));
        chainMapper.delete(Wrappers.<ApprovalChainConfig>lambdaQuery().eq(ApprovalChainConfig::getBizType, bizType));
        for (ApprovalProcessTemplate template : templateMapper.selectList(
                Wrappers.<ApprovalProcessTemplate>lambdaQuery().eq(ApprovalProcessTemplate::getBizType, bizType))) {
            versionMapper.delete(Wrappers.<ApprovalProcessTemplateVersion>lambdaQuery()
                    .eq(ApprovalProcessTemplateVersion::getTemplateId, template.getId()));
            templateMapper.deleteById(template.getId());
        }
        if (group != null) {
            groupMemberMapper.delete(Wrappers.<UserGroupMember>lambdaQuery()
                    .eq(UserGroupMember::getGroupId, group.id()));
            groupMapper.deleteById(group.id());
        }
        for (Long userId : Arrays.asList(alice, bob, carol)) {
            if (userId != null) {
                users.deleteById(userId);
            }
        }
    }

    /** 读取指定用户在本例业务上的唯一待办。 */
    private ApprovalTaskView task(Long userId) {
        return gateway.listPendingTasks(userId.toString()).stream()
                .filter(task -> bizId.equals(task.bizId())).findFirst().orElseThrow();
    }

    /** 读取指定用户在本例业务上的唯一审批记录。 */
    private ApprovalRecordView record(Long userId) {
        return gateway.listApprovalRecords(userId.toString()).stream()
                .filter(record -> bizId.equals(record.bizId())).findFirst().orElseThrow();
    }

    /** 构建指定路径模板，按需追加个人节点验证混合指派。 */
    private void publish(boolean withPersonal) {
        List<ProcessDesignNode> nodes = new ArrayList<>();
        ProcessDesignNode start = node("start", "START");
        ProcessDesignNode approval = node("group", "APPROVAL");
        approval.setAssigneeType("GROUP");
        approval.setApproverGroupId(group.id());
        nodes.add(start);
        nodes.add(approval);
        if (withPersonal) {
            ProcessDesignNode personal = node("personal", "APPROVAL");
            personal.setApproverUserId(alice.toString());
            nodes.add(personal);
        }
        nodes.add(node("end", "END"));
        List<ProcessDesignEdge> edges = new ArrayList<>();
        for (int index = 1; index < nodes.size(); index++) {
            ProcessDesignEdge edge = new ProcessDesignEdge();
            edge.setId("edge-" + index);
            edge.setSourceNodeId(nodes.get(index - 1).getId());
            edge.setTargetNodeId(nodes.get(index).getId());
            edges.add(edge);
        }
        ProcessTemplateDraftSaveRequest request = new ProcessTemplateDraftSaveRequest();
        request.setExpectedDraftRevision(0L);
        request.setNodes(nodes);
        request.setEdges(edges);
        ProcessTemplateVO saved = templates.saveDraft(bizType, "GLOBAL", request);
        templates.publish(bizType, "GLOBAL", saved.draftRevision());
    }

    /** 创建测试画布节点，布局不影响执行顺序。 */
    private ProcessDesignNode node(String id, String type) {
        ProcessDesignNode node = new ProcessDesignNode();
        node.setId(id);
        node.setType(type);
        node.setX(20.0);
        node.setY(20.0);
        return node;
    }

    /** 创建有效测试用户，直接写测试夹具避免触发原业务审批。 */
    private Long user(String label) {
        SysUser user = new SysUser();
        user.setUsername(label + UUID.randomUUID());
        user.setRealName(label);
        user.setStatus("ACTIVE");
        user.setCreatedTime(LocalDateTime.now());
        user.setUpdatedTime(LocalDateTime.now());
        users.insert(user);
        return user.getId();
    }

    /** 使用最新编辑版本整体替换组成员及状态。 */
    private void replace(List<Long> members, String status) {
        group = groups.save(group.id(), new UserGroupRequest(group.code(), group.name(), "",
                status, group.revision(), members), "test");
    }

    /** 发起本例唯一业务审批。 */
    private void start() {
        gateway.start(new StartApprovalCommand(bizType, bizId, "test", "{}"));
    }

    /** 提交指定测试用户的审批动作。 */
    private void act(Long userId, ApprovalAction action) {
        gateway.act(new ApprovalActCommand(bizType, bizId, userId.toString(), action, "测试意见"));
    }

    /** 检查当前用户是否拥有本例业务待办。 */
    private boolean pending(Long userId) {
        return gateway.listPendingTasks(userId.toString()).stream().anyMatch(task -> bizId.equals(task.bizId()));
    }

    /** 按用户访问权限读取本例审批详情。 */
    private ApprovalDetailView detail(Long userId) {
        return gateway.getApprovalDetail(links.findLatestByBizKey(bizType, bizId).orElseThrow().getId(), userId.toString());
    }
}
