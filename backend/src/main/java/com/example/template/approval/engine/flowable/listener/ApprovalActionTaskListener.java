package com.example.template.approval.engine.flowable.listener;

import java.io.Serial;

import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 绑定在“审批”用户任务的 {@code complete} 事件上：只把当前审批人本级的同意/驳回动作与意见
 * 记录为流程变量（供审计追溯），不发布事件、不调用任何业务代码。
 * <p>
 * 用于驱动 {@code completionCondition}（任一级驳回立即终止循环）的控制变量由网关随任务完成参数传入。
 * 本监听器有意再次通过非 Local 的 {@link DelegateTask#setVariable(String, Object)} 写入最终动作、意见及
 * 分级审计变量，保证多实例执行上下文和流程结束监听器均可向上解析到最新结果。
 * <p>
 * 禁止改为 {@code setVariableLocal}：任务局部变量会随任务完成而消失，可能使完成条件无法识别驳回，
 * 继续错误创建后续审批任务，也会使结束监听器无法读取最终动作。
 */
public class ApprovalActionTaskListener implements TaskListener {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalActionTaskListener.class);

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public void notify(DelegateTask delegateTask) {
        // loopCounter 是顺序多实例“子执行”上的本地变量，任务本身直接绑定在该子执行上，
        // 因此用非 Local 的 getVariable 从任务所在执行开始逐级查找即可读到（getVariableLocal
        // 查的是“任务”自身的变量作用域，不是任务所在的执行，读不到 loopCounter）。
        Integer loopCounter = (Integer) delegateTask.getVariable("loopCounter");
        int level = loopCounter == null ? 1 : loopCounter + 1;

        Object action = delegateTask.getVariable("approvalAction");
        Object comment = delegateTask.getVariable("approvalComment");

        logger.info("loopCounter [{}] level [{}] action [{}] comment [{}]", loopCounter, level, action, comment);

        // 必须使用非 Local API，让完成条件和结束监听器能从其执行上下文解析最终审批结果。
        delegateTask.setVariable("approvalAction", action);
        delegateTask.setVariable("approvalComment", comment);

        // 分级变量同样写入可向流程树解析的作用域，确保任务完成后仍可用于审计。
        delegateTask.setVariable("approvalLevel" + level + "Action", action);
        delegateTask.setVariable("approvalLevel" + level + "Comment", comment);
    }
}
