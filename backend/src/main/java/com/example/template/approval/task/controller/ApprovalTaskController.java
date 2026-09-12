package com.example.template.approval.task.controller;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.template.approval.api.ApprovalGateway;
import com.example.template.approval.api.dto.ApprovalActCommand;
import com.example.template.approval.api.dto.ApprovalDetailView;
import com.example.template.approval.api.dto.ApprovalRecordView;
import com.example.template.approval.api.dto.ApprovalTaskView;
import com.example.template.approval.task.dto.ApprovalActRequest;
import com.example.template.common.RestResult;
import com.example.template.operator.CurrentOperator;
import com.example.template.operator.OperatorContext;

/**
 * 审批人待办查询与同意/驳回操作接口（对应 design.md D9）。只依赖 {@code approval.api} 的
 * {@link ApprovalGateway}，自身不直接 import 任何 {@code org.flowable.*} 类型，
 * 具体工作流引擎交互全部在 {@code approval.engine.flowable} 内完成。审批人身份统一通过
 * {@link CurrentOperator} 从请求头解析（见 operator-header-identity 变更 design.md D3）。
 */
@RestController
@RequiredArgsConstructor
public class ApprovalTaskController {

    private final ApprovalGateway approvalGateway;

    /**
     * 查询当前操作人的待办审批任务列表。
     *
     * @param operator 当前操作人上下文
     * @return 当前操作人的待办审批任务
     */
    @GetMapping("/api/approval/tasks")
    public RestResult<List<ApprovalTaskView>> listPendingTasks(@CurrentOperator OperatorContext operator) {
        return RestResult.success(approvalGateway.listPendingTasks(operator.userId()));
    }

    /**
     * 查询当前操作人已经完成的审批记录。
     *
     * @param operator 当前操作人上下文
     * @return 当前操作人的审批记录
     */
    @GetMapping("/api/approval/records")
    public RestResult<List<ApprovalRecordView>> listApprovalRecords(@CurrentOperator OperatorContext operator) {
        return RestResult.success(approvalGateway.listApprovalRecords(operator.userId()));
    }

    /**
     * 查询当前操作人有权访问的审批实例详情。
     *
     * @param approvalId 审批实例标识
     * @param operator   当前操作人上下文
     * @return 审批实例详情
     */
    @GetMapping("/api/approval/instances/{approvalId}")
    public RestResult<ApprovalDetailView> getApprovalDetail(
            @PathVariable Long approvalId,
            @CurrentOperator OperatorContext operator) {
        return RestResult.success(approvalGateway.getApprovalDetail(approvalId, operator.userId()));
    }

    /**
     * 当前操作人提交同意/驳回操作。
     *
     * @param request  审批动作请求
     * @param operator 当前操作人上下文
     * @return 不包含业务结果数据的成功响应
     */
    @PostMapping("/api/approval/tasks/act")
    public RestResult<Void> act(@Valid @RequestBody ApprovalActRequest request,
                                 @CurrentOperator OperatorContext operator) {
        approvalGateway.act(new ApprovalActCommand(request.getBizType(), request.getBizId(),
                operator.userId(), request.getAction(), request.getComment()));
        return RestResult.success(null);
    }
}
