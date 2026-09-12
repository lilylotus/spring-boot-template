package com.example.template.approval.task.dto;

import com.example.template.approval.api.dto.ApprovalAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 审批人提交同意/驳回操作的请求体。除审批人标识（改由 {@code @CurrentOperator} 从请求头解析，
 * 见 operator-header-identity 变更 design.md D3）外，其余字段与
 * {@link com.example.template.approval.api.dto.ApprovalActCommand} 一一对应，在 Controller 中
 * 转换为 {@code ApprovalActCommand} 后调用
 * {@link com.example.template.approval.api.ApprovalGateway#act(com.example.template.approval.api.dto.ApprovalActCommand)}。
 */
@Data
public class ApprovalActRequest {

    @NotBlank(message = "业务动作类型不能为空")
    private String bizType;

    @NotBlank(message = "业务单据标识不能为空")
    private String bizId;

    @NotNull(message = "审批动作（同意/驳回）不能为空")
    private ApprovalAction action;

    /** 审批意见，可为空。 */
    private String comment;
}
