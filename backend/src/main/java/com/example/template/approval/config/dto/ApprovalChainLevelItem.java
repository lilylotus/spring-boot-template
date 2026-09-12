package com.example.template.approval.config.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 保存审批链时，单个级别的请求参数。
 */
@Data
public class ApprovalChainLevelItem {

    @NotNull(message = "审批级别不能为空")
    @Min(value = 1, message = "审批级别必须从1开始")
    private Integer levelNo;

    @NotBlank(message = "审批人不能为空")
    private String approverUserId;
}
