package com.example.template.approval.config.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新审批全局开关请求。
 */
@Data
public class ApprovalSwitchUpdateRequest {

    @NotNull(message = "审批开关状态不能为空")
    private Boolean approvalEnabled;
}
