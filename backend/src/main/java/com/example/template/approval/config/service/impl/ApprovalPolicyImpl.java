package com.example.template.approval.config.service.impl;

import com.example.template.approval.api.ApprovalPolicy;
import com.example.template.approval.config.service.ApprovalConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link ApprovalPolicy} 的具体实现：读取 {@code approval_switch} 单行总开关。
 * <p>
 * 当前需求下审批开关是一个总开关，不区分 {@code bizType}，因此本实现忽略入参 {@code bizType}；
 * 若未来需要按业务类型拆分开关，只需改动 {@code approval_switch} 表结构与本类，调用方不受影响。
 */
@Component
@RequiredArgsConstructor
public class ApprovalPolicyImpl implements ApprovalPolicy {

    private final ApprovalConfigService approvalConfigService;

    @Override
    public boolean isEnabled(String bizType) {
        return approvalConfigService.isApprovalEnabled();
    }
}
