package com.example.template.identity;

import com.example.template.approval.api.event.ApprovalResultEvent;
import com.example.template.identity.constant.UserBizType;
import com.example.template.identity.listener.UserApprovalResultListener;
import com.example.template.identity.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证 {@link UserApprovalResultListener} 按 bizType 正确分发到 {@link UserService}，
 * 且重复消费同一事件时不会抛出异常（真正的状态幂等性由 {@code UserServiceImpl} 内部保证，
 * 见 {@code UserServiceImplTest}）。
 */
@ExtendWith(MockitoExtension.class)
class UserApprovalResultListenerTest {

    @Mock
    private UserService userService;

    @Test
    void onApprovalResult_dispatchesUserCreate_andToleratesRepeatedConsumption() {
        UserApprovalResultListener listener = new UserApprovalResultListener(userService);
        ApprovalResultEvent event = new ApprovalResultEvent(this, UserBizType.USER_CREATE, "123", true, "ok");

        listener.onApprovalResult(event);
        listener.onApprovalResult(event); // 模拟消息重复投递/重复消费同一事件

        verify(userService, times(2)).applyCreateApprovalResult("123", true);
        verify(userService, never()).applyEditApprovalResult(anyString(), anyBoolean());
    }

    @Test
    void onApprovalResult_dispatchesUserEdit() {
        UserApprovalResultListener listener = new UserApprovalResultListener(userService);
        ApprovalResultEvent event = new ApprovalResultEvent(this, UserBizType.USER_EDIT, "456", false, "驳回");

        listener.onApprovalResult(event);

        verify(userService, times(1)).applyEditApprovalResult("456", false);
        verify(userService, never()).applyCreateApprovalResult(anyString(), anyBoolean());
    }

    @Test
    void onApprovalResult_ignoresUnrelatedBizType() {
        UserApprovalResultListener listener = new UserApprovalResultListener(userService);
        ApprovalResultEvent event = new ApprovalResultEvent(this, "SOME_OTHER_BIZ", "789", true, "ok");

        listener.onApprovalResult(event);

        verifyNoInteractions(userService);
    }
}
