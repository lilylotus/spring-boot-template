package com.example.template.approval.api.event;

import org.springframework.context.ApplicationEvent;

/**
 * 审批结果的领域事件：由审批引擎适配层（{@code approval.engine.flowable}）在审批产生最终结果
 * （通过/驳回）后发布，是一个不依赖任何具体工作流引擎类型的纯 POJO 事件。
 * <p>
 * 发布方（Flowable 的 {@code ExecutionListener}）不知道、也不关心谁会消费这个事件；
 * 业务模块（如 {@code identity}）通过 Spring 事件监听机制订阅并据此更新自身数据状态。
 */
public class ApprovalResultEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final String bizType;
    private final String bizId;
    private final boolean approved;
    private final String comment;

    public ApprovalResultEvent(Object source, String bizType, String bizId, boolean approved, String comment) {
        super(source);
        this.bizType = bizType;
        this.bizId = bizId;
        this.approved = approved;
        this.comment = comment;
    }

    public String getBizType() {
        return bizType;
    }

    public String getBizId() {
        return bizId;
    }

    public boolean isApproved() {
        return approved;
    }

    public String getComment() {
        return comment;
    }
}
