package com.example.template.approval.config.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 保存（整体替换）某个业务动作审批链的请求：审批级别需从1开始连续、每级审批人不能为空，
 * 具体连续性等跨字段业务校验在 Service 层完成。
 */
@Data
public class ApprovalChainSaveRequest {

    @NotEmpty(message = "审批链级别不能为空")
    @Valid
    private List<ApprovalChainLevelItem> levels;
}
