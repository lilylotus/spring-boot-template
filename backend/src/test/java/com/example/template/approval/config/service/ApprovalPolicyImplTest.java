package com.example.template.approval.config.service;

import com.example.template.approval.config.service.impl.ApprovalPolicyImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link ApprovalPolicyImpl} 忽略 bizType 参数、只依据 approval_switch 总开关返回结果。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalPolicyImplTest {

    @Mock
    private ApprovalConfigService approvalConfigService;

    @Test
    void isEnabled_returnsTrue_whenSwitchOn() {
        when(approvalConfigService.isApprovalEnabled()).thenReturn(true);
        ApprovalPolicyImpl policy = new ApprovalPolicyImpl(approvalConfigService);

        assertThat(policy.isEnabled("USER_CREATE")).isTrue();
        assertThat(policy.isEnabled("USER_EDIT")).isTrue();
    }

    @Test
    void isEnabled_returnsFalse_whenSwitchOff() {
        when(approvalConfigService.isApprovalEnabled()).thenReturn(false);
        ApprovalPolicyImpl policy = new ApprovalPolicyImpl(approvalConfigService);

        assertThat(policy.isEnabled("USER_CREATE")).isFalse();
    }
}
