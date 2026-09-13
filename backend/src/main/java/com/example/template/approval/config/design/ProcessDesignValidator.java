package com.example.template.approval.config.design;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.template.common.BusinessException;

/** 验证单路径画布并生成有序指派计划，不读取用户组成员或依赖引擎类型。 */
@Component
public class ProcessDesignValidator {
    private static final int MAX_ELEMENTS = 50;

    /** 验证节点、连线和指派目标，返回按路径排序的个人ID或组引用。 */
    public List<String> validateAndResolveApprovers(ProcessDesignModel model) {
        if (model == null || model.getNodes() == null || model.getEdges() == null) {
            throw new BusinessException("流程模型不能为空");
        }
        if (model.getNodes().size() > MAX_ELEMENTS || model.getEdges().size() > MAX_ELEMENTS) {
            throw new BusinessException("流程节点或连线不能超过50个");
        }
        Map<String, ProcessDesignNode> nodes = new HashMap<>();
        for (ProcessDesignNode node : model.getNodes()) {
            if (node == null || !StringUtils.hasText(node.getId()) || nodes.put(node.getId(), node) != null) {
                throw new BusinessException("流程节点ID不能为空或重复");
            }
            if (node.getType() == null || !Set.of("START", "APPROVAL", "END").contains(node.getType())) {
                throw new BusinessException("存在不支持的流程节点类型");
            }
        }
        List<ProcessDesignNode> starts = nodes.values().stream().filter(n -> "START".equals(n.getType())).toList();
        List<ProcessDesignNode> ends = nodes.values().stream().filter(n -> "END".equals(n.getType())).toList();
        long approvalCount = nodes.values().stream().filter(n -> "APPROVAL".equals(n.getType())).count();
        if (starts.size() != 1 || ends.size() != 1 || approvalCount == 0) {
            throw new BusinessException("流程必须包含一个开始节点、至少一个审批节点和一个结束节点");
        }

        Map<String, String> outgoing = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        Set<String> edgeKeys = new HashSet<>();
        for (ProcessDesignEdge edge : model.getEdges()) {
            if (edge == null || !StringUtils.hasText(edge.getId())
                    || !nodes.containsKey(edge.getSourceNodeId()) || !nodes.containsKey(edge.getTargetNodeId())) {
                throw new BusinessException("流程连线端点不存在");
            }
            if (edge.getSourceNodeId().equals(edge.getTargetNodeId())) {
                throw new BusinessException("流程不允许自环");
            }
            String edgeKey = edge.getSourceNodeId() + "->" + edge.getTargetNodeId();
            if (!edgeKeys.add(edgeKey) || outgoing.put(edge.getSourceNodeId(), edge.getTargetNodeId()) != null) {
                throw new BusinessException("流程不允许重复连线或分叉");
            }
            incoming.merge(edge.getTargetNodeId(), 1, Integer::sum);
            if (incoming.get(edge.getTargetNodeId()) > 1) {
                throw new BusinessException("流程不允许汇聚");
            }
        }

        String startId = starts.get(0).getId();
        String endId = ends.get(0).getId();
        if (incoming.getOrDefault(startId, 0) != 0 || !outgoing.containsKey(startId)
                || incoming.getOrDefault(endId, 0) != 1 || outgoing.containsKey(endId)) {
            throw new BusinessException("开始或结束节点连接不完整");
        }

        List<String> approvers = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = startId;
        while (!current.equals(endId)) {
            if (!visited.add(current)) {
                throw new BusinessException("流程不允许出现环路");
            }
            String next = outgoing.get(current);
            if (next == null) {
                throw new BusinessException("流程存在断开的节点");
            }
            ProcessDesignNode nextNode = nodes.get(next);
            if ("APPROVAL".equals(nextNode.getType())) {
                if ("GROUP".equals(nextNode.getAssigneeType())) {
                    if (nextNode.getApproverGroupId() == null || nextNode.getApproverGroupId() <= 0
                            || StringUtils.hasText(nextNode.getApproverUserId())) {
                        throw new BusinessException("用户组节点必须且只能选择一个用户组");
                    }
                    approvers.add(GroupAssignment.encode(nextNode.getApproverGroupId()));
                } else if (nextNode.getAssigneeType() != null && !"USER".equals(nextNode.getAssigneeType())) {
                    throw new BusinessException("不支持的审批指派类型");
                } else if (!StringUtils.hasText(nextNode.getApproverUserId())) {
                    throw new BusinessException("每个审批节点都必须选择审批人");
                } else {
                    if (nextNode.getApproverGroupId() != null || GroupAssignment.isGroup(nextNode.getApproverUserId())) {
                        throw new BusinessException("指定用户节点不能包含用户组引用");
                    }
                    approvers.add(nextNode.getApproverUserId());
                }
            } else if (!"END".equals(nextNode.getType())) {
                throw new BusinessException("开始节点只能位于流程起点");
            }
            current = next;
        }
        visited.add(endId);
        if (visited.size() != nodes.size() || approvers.size() != approvalCount) {
            throw new BusinessException("流程包含未连接节点");
        }
        return approvers;
    }
}
