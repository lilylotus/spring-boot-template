package com.example.template.approval.config.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审批全局开关查询响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalSwitchVO {

    private boolean approvalEnabled;
}
