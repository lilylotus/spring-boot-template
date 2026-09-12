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
