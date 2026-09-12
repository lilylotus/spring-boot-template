package com.example.template.approval.config.service;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.template.approval.config.entity.ApprovalChainConfig;
import com.example.template.approval.config.entity.ApprovalSwitch;
import com.example.template.approval.config.mapper.ApprovalChainConfigMapper;
import com.example.template.approval.config.mapper.ApprovalSwitchMapper;
import com.example.template.approval.config.service.impl.ApprovalConfigServiceImpl;
import com.example.template.common.BusinessException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 审批配置服务测试，验证全局开关开启前的受控审批链完整性约束。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalConfigServiceImplTest {

    @Mock
    private ApprovalSwitchMapper approvalSwitchMapper;

    @Mock
    private ApprovalChainConfigMapper approvalChainConfigMapper;

    private ApprovalConfigServiceImpl approvalConfigService;

    @BeforeEach
    void setUp() {
        approvalConfigService = new ApprovalConfigServiceImpl(approvalSwitchMapper, approvalChainConfigMapper);
    }

    @Test
    void updateApprovalSwitch_enablesWhenBothRequiredChainsAreComplete() {
        when(approvalChainConfigMapper.selectList(any()))
                .thenReturn(List.of(newLevel("USER_CREATE", 1)))
                .thenReturn(List.of(newLevel("USER_EDIT", 1)));
        when(approvalSwitchMapper.updateById(any(ApprovalSwitch.class))).thenReturn(1);

        approvalConfigService.updateApprovalSwitch(true);

        verify(approvalSwitchMapper).updateById(
                argThat((ApprovalSwitch approvalSwitch) -> Boolean.TRUE.equals(approvalSwitch.getApprovalEnabled())));
        verify(approvalSwitchMapper, never()).insert(any(ApprovalSwitch.class));
    }

    @Test
    void updateApprovalSwitch_rejectsWhenUserCreateChainIsMissingWithoutUpdatingSwitch() {
        when(approvalChainConfigMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> approvalConfigService.updateApprovalSwitch(true))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无法开启审批流程：用户新增审批链未配置");

        verifyNoInteractions(approvalSwitchMapper);
    }

    @Test
    void updateApprovalSwitch_rejectsWhenUserEditChainIsMissingWithoutUpdatingSwitch() {
        when(approvalChainConfigMapper.selectList(any()))
                .thenReturn(List.of(newLevel("USER_CREATE", 1)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> approvalConfigService.updateApprovalSwitch(true))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无法开启审批流程：用户编辑审批链未配置");

        verifyNoInteractions(approvalSwitchMapper);
    }

    @Test
    void updateApprovalSwitch_disablesWithoutCheckingRequiredChains() {
        when(approvalSwitchMapper.updateById(any(ApprovalSwitch.class))).thenReturn(1);

        approvalConfigService.updateApprovalSwitch(false);

        verifyNoInteractions(approvalChainConfigMapper);
        verify(approvalSwitchMapper).updateById(
                argThat((ApprovalSwitch approvalSwitch) -> Boolean.FALSE.equals(approvalSwitch.getApprovalEnabled())));
    }

    private ApprovalChainConfig newLevel(String bizType, int levelNo) {
        ApprovalChainConfig level = new ApprovalChainConfig();
        level.setBizType(bizType);
        level.setLevelNo(levelNo);
        level.setApproverUserId("approver");
        level.setCreatedTime(LocalDateTime.now());
        level.setUpdatedTime(LocalDateTime.now());
        return level;
    }
}
