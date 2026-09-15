package com.example.template.outbox.event;

/**
 * 请假审批完成的领域事件，由触发审批完成的地方发布(比如Flowable审批引擎的回调)，
 * 业务侧的{@code ApprovalFinishedEventListener}监听后在自己的事务里更新业务表并写入Outbox记录。
 */
public class ApprovalFinishedEvent {

    /** 对应{@code LeaveRequest#getId()}。 */
    private final Long businessKey;

    private final boolean approved;

    public ApprovalFinishedEvent(Long businessKey, boolean approved) {
        this.businessKey = businessKey;
        this.approved = approved;
    }

    public Long getBusinessKey() {
        return businessKey;
    }

    public boolean isApproved() {
        return approved;
    }

}
