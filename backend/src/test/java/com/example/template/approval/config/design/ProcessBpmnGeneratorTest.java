package com.example.template.approval.config.design;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证审批流程 BPMN 的单级顺序执行模型及运行时监听器绑定。 */
class ProcessBpmnGeneratorTest {

    private final ProcessBpmnGenerator generator = new ProcessBpmnGenerator();

    /** 普通审批节点与组审批节点各占一个有序集合元素，名称中的 XML 字符须安全转义。 */
    @Test
    void createsExecutableSequentialApprovalProcessAndEscapesName() {
        String xml = generator.generate("approvalTemplate_7", "用户新增<&审批");

        assertThat(xml)
                .contains("<process id=\"approvalTemplate_7\"")
                .contains("name=\"用户新增&lt;&amp;审批\"")
                .contains("multiInstanceLoopCharacteristics isSequential=\"true\"")
                .contains("flowable:collection=\"approverList\"")
                .contains("flowable:elementVariable=\"currentApprover\"")
                .contains("flowable:assignee=\"${currentApprover}\"")
                .contains("ApprovalActionTaskListener")
                .contains("ApprovalResultExecutionListener");
    }

    /** 生成的审批任务必须在创建事件上挂载组任务监听器，才能把组引用转成共享候选任务。 */
    @Test
    void attachesGroupTaskCreateListenerOnTaskCreate() {
        String xml = generator.generate("approvalTemplate_8", "用户组审批");

        assertThat(xml).contains("<flowable:taskListener event=\"create\" "
                + "class=\"com.example.template.approval.engine.flowable.listener.GroupTaskCreateListener\"/>");
    }
}
