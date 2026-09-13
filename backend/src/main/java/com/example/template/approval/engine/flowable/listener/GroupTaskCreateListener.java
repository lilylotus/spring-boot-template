package com.example.template.approval.engine.flowable.listener;

import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import com.example.template.approval.config.design.GroupAssignment;

/** 将组引用转换为共享候选任务；成员关系始终由业务用户组服务实时查询。 */
public class GroupTaskCreateListener implements TaskListener {
    /** 个人节点保持原指派，组节点移除个人受理人并记录候选组标识。 */
    @Override
    public void notify(DelegateTask task) {
        String assignment = (String) task.getVariable("currentApprover");
        if (GroupAssignment.isGroup(assignment)) {
            task.setAssignee(null);
            task.addCandidateGroup(assignment);
        }
    }
}
