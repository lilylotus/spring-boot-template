package com.example.template.outbox.entity;

/**
 * {@link LeaveRequest#getStatus()} 的取值常量，对应请假审批流程的三个状态。
 */
public final class LeaveRequestStatus {

    /** 审批中，等待审批结果。只有处于该状态的记录才会被审批完成事件更新。 */
    public static final String APPROVING = "APPROVING";

    public static final String APPROVED = "APPROVED";

    public static final String REJECTED = "REJECTED";

    private LeaveRequestStatus() {
    }

}
