package com.example.template.approval.config.design;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessBpmnGeneratorTest {

    private final ProcessBpmnGenerator generator = new ProcessBpmnGenerator();

    @Test
    void createsExecutableSequentialApprovalProcessAndEscapesName() {
        String xml = generator.generate("approvalTemplate_7", "用户新增<&审批");

        assertThat(xml)
                .contains("<process id=\"approvalTemplate_7\"")
                .contains("name=\"用户新增&lt;&amp;审批\"")
                .contains("multiInstanceLoopCharacteristics isSequential=\"true\"")
                .contains("flowable:collection=\"approverList\"")
                .contains("ApprovalActionTaskListener")
                .contains("ApprovalResultExecutionListener");
    }
}
