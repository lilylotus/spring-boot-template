package com.example.template.approval.engine.flowable.listener;

import com.example.template.approval.api.dto.ApprovalStatus;
import com.example.template.approval.api.event.ApprovalResultEvent;
import com.example.template.approval.engine.flowable.SpringContextHolder;
import com.example.template.approval.link.service.ApprovalBizLinkService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 绑定在流程的 {@code end} 事件上：读取流程变量得出最终审批结果，
 * 1) 同步更新审批自身维护的 {@code approval_biz_link} 状态（D6：由本类“收到最终结果”的时机完成）；
 * 2) 通过 {@link SpringContextHolder} 拿到 {@link ApplicationContext}（其本身即实现了
 *    {@link ApplicationEventPublisher}，Spring 不会把它单独注册为可通过
 *    {@code getBean(ApplicationEventPublisher.class)} 查到的 Bean）发布
 *    {@link ApprovalResultEvent} —— 一个不依赖 {@code org.flowable.*} 的纯 POJO 事件。
 * <p>
 * 本类完成的工作到此为止，不知道、也不关心谁会消费该事件；不直接调用任何业务模块（如
 * {@code identity}）的 Service，保持监听器与具体业务模块解耦。
 */
public class ApprovalResultExecutionListener implements ExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResultExecutionListener.class);

    private static final long serialVersionUID = 1L;

    @Override
    public void notify(DelegateExecution execution) {
        String bizType = (String) execution.getVariable("bizType");
        String bizId = (String) execution.getVariable("bizId");
        String approvalAction = (String) execution.getVariable("approvalAction");
        String comment = (String) execution.getVariable("approvalComment");
        boolean approved = "AGREE".equals(approvalAction);
        log.info("bizType [{}] bizId [{}] approvalAction [{}] comment [{}] approved [{}]",
                bizType, bizId, approvalAction, comment, approved);

        ApprovalBizLinkService approvalBizLinkService = SpringContextHolder.getBean(ApprovalBizLinkService.class);
        approvalBizLinkService.findByProcessInstanceId(execution.getProcessInstanceId())
                .ifPresent(link -> approvalBizLinkService.updateStatus(link.getId(),
                        approved ? ApprovalStatus.APPROVED.name() : ApprovalStatus.REJECTED.name()));

        ApplicationEventPublisher eventPublisher = SpringContextHolder.getApplicationContext();
        eventPublisher.publishEvent(new ApprovalResultEvent(this, bizType, bizId, approved, comment));
    }
}
