package com.example.template.approval.config.design;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.template.common.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessDesignValidatorTest {

    private final ProcessDesignValidator validator = new ProcessDesignValidator();

    @Test
    void resolvesApproversInGraphOrder() {
        ProcessDesignModel model = model(
                List.of(node("start", "START", null), node("second", "APPROVAL", "user-b"),
                        node("first", "APPROVAL", "user-a"), node("end", "END", null)),
                List.of(edge("start", "first"), edge("first", "second"), edge("second", "end")));

        assertThat(validator.validateAndResolveApprovers(model)).containsExactly("user-a", "user-b");
    }

    @Test
    void rejectsDisconnectedNodes() {
        ProcessDesignModel model = model(
                List.of(node("start", "START", null), node("approval", "APPROVAL", "user-a"),
                        node("orphan", "APPROVAL", "user-b"), node("end", "END", null)),
                List.of(edge("start", "approval"), edge("approval", "end")));

        assertThatThrownBy(() -> validator.validateAndResolveApprovers(model))
                .isInstanceOf(BusinessException.class)
                .hasMessage("流程包含未连接节点");
    }

    @Test
    void rejectsForks() {
        ProcessDesignModel model = model(
                List.of(node("start", "START", null), node("approval-a", "APPROVAL", "user-a"),
                        node("approval-b", "APPROVAL", "user-b"), node("end", "END", null)),
                List.of(edge("start", "approval-a"), edge("start", "approval-b"),
                        edge("approval-a", "end")));

        assertThatThrownBy(() -> validator.validateAndResolveApprovers(model))
                .isInstanceOf(BusinessException.class)
                .hasMessage("流程不允许重复连线或分叉");
    }

    @Test
    void rejectsApprovalWithoutApprover() {
        ProcessDesignModel model = model(
                List.of(node("start", "START", null), node("approval", "APPROVAL", null),
                        node("end", "END", null)),
                List.of(edge("start", "approval"), edge("approval", "end")));

        assertThatThrownBy(() -> validator.validateAndResolveApprovers(model))
                .isInstanceOf(BusinessException.class)
                .hasMessage("每个审批节点都必须选择审批人");
    }

    /** 混合指定用户与用户组的画布应通过校验，并按连线路径而非节点声明顺序解析。 */
    @Test
    void resolvesMixedUserAndGroupNodesInGraphOrder() {
        ProcessDesignModel model = model(
                List.of(node("start", "START", null), userNode("personal", "user-a"),
                        groupNode("team", 3L), node("end", "END", null)),
                List.of(edge("start", "team"), edge("team", "personal"), edge("personal", "end")));

        assertThat(validator.validateAndResolveApprovers(model)).containsExactly("group:3", "user-a");
        assertThat(validator.validateAndResolveNodes(model)).containsExactly(
                new ApprovalNodeSnapshot("team", "GROUP", null, 3L),
                new ApprovalNodeSnapshot("personal", "USER", "user-a", null));
    }

    /** 用户组节点缺少组ID时拒绝发布。 */
    @Test
    void rejectsGroupNodeWithoutGroupId() {
        assertThatThrownBy(() -> validator.validateAndResolveApprovers(single(groupNode("team", null))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户组节点必须且只能选择一个用户组");
    }

    /** 用户组节点同时填写用户和组属于指派冲突。 */
    @Test
    void rejectsGroupNodeWithBothUserAndGroup() {
        ProcessDesignNode conflict = groupNode("team", 3L);
        conflict.setApproverUserId("user-a");

        assertThatThrownBy(() -> validator.validateAndResolveApprovers(single(conflict)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户组节点必须且只能选择一个用户组");
    }

    /** 未知指派类型不能被静默当作指定用户。 */
    @Test
    void rejectsUnsupportedAssigneeType() {
        ProcessDesignNode unknown = userNode("approval", "user-a");
        unknown.setAssigneeType("ROLE");

        assertThatThrownBy(() -> validator.validateAndResolveApprovers(single(unknown)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("不支持的审批指派类型");
    }

    /** 指定用户节点携带组ID或占用组引用前缀均被拒绝。 */
    @Test
    void rejectsUserNodeWithGroupReference() {
        ProcessDesignNode withGroupId = userNode("approval", "user-a");
        withGroupId.setApproverGroupId(3L);

        assertThatThrownBy(() -> validator.validateAndResolveApprovers(single(withGroupId)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("指定用户节点不能包含用户组引用");
        assertThatThrownBy(() -> validator.validateAndResolveApprovers(single(userNode("approval", "group:3"))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("指定用户节点不能包含用户组引用");
    }

    /** 缺少指派类型的旧节点按指定用户兼容。 */
    @Test
    void treatsLegacyNodeWithoutAssigneeTypeAsUser() {
        ProcessDesignModel model = single(node("approval", "APPROVAL", "user-a"));

        assertThat(validator.validateAndResolveApprovers(model)).containsExactly("user-a");
        assertThat(validator.validateAndResolveNodes(model)).containsExactly(
                new ApprovalNodeSnapshot("approval", "USER", "user-a", null));
    }

    /** 构建只含一个审批节点的完整画布。 */
    private ProcessDesignModel single(ProcessDesignNode approval) {
        return model(List.of(node("start", "START", null), approval, node("end", "END", null)),
                List.of(edge("start", approval.getId()), edge(approval.getId(), "end")));
    }

    /** 创建显式声明为指定用户的审批节点。 */
    private ProcessDesignNode userNode(String id, String approver) {
        ProcessDesignNode node = node(id, "APPROVAL", approver);
        node.setAssigneeType("USER");
        return node;
    }

    /** 创建用户组审批节点，组ID可为空以构造非法配置。 */
    private ProcessDesignNode groupNode(String id, Long groupId) {
        ProcessDesignNode node = node(id, "APPROVAL", null);
        node.setAssigneeType("GROUP");
        node.setApproverGroupId(groupId);
        return node;
    }

    private ProcessDesignModel model(List<ProcessDesignNode> nodes, List<ProcessDesignEdge> edges) {
        ProcessDesignModel model = new ProcessDesignModel();
        model.setNodes(nodes);
        model.setEdges(edges);
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
