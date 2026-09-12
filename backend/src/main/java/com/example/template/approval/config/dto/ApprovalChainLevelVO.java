package com.example.template.approval.config.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审批链中一个级别的配置。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalChainLevelVO {

    private Integer levelNo;

    private String approverUserId;
}
