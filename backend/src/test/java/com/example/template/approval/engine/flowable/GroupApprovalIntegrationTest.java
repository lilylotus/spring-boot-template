package com.example.template.approval.engine.flowable;

import java.time.LocalDateTime;
import java.util.*;
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
