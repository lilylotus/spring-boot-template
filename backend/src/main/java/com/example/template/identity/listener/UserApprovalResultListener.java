package com.example.template.identity.listener;

import com.example.template.approval.api.event.ApprovalResultEvent;
import com.example.template.identity.constant.UserBizType;
import com.example.template.identity.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 被动消费 {@link ApprovalResultEvent}，驱动用户管理模块完成“待审批 -> 已生效/已驳回”的状态流转。
 * <p>
 * 使用 {@code AFTER_COMMIT}：确保只有在触发该事件的 Flowable 事务（审批人完成最后一级任务）
 * 已经提交之后，才去更新业务状态，避免读到尚未提交、之后可能回滚的中间态（design.md D7）。
 * 幂等性由 {@link UserService} 内部对当前状态是否仍为“待审批”的判断保证。
 */
@Component
@RequiredArgsConstructor
public class UserApprovalResultListener {

    private final UserService userService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApprovalResult(ApprovalResultEvent event) {
        if (UserBizType.USER_CREATE.equals(event.getBizType())) {
            userService.applyCreateApprovalResult(event.getBizId(), event.isApproved());
        } else if (UserBizType.USER_EDIT.equals(event.getBizType())) {
            userService.applyEditApprovalResult(event.getBizId(), event.isApproved());
        }
        // 其余 bizType 不属于用户管理模块，本监听器不处理
    }
}
