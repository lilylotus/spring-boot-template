package com.example.template.approval.config.design;

import org.springframework.stereotype.Component;

@Component
public class ProcessBpmnGenerator {

    public String generate(String processKey, String processName) {
        String safeName = escapeXml(processName);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://com.example.template/approval">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="startEvent" name="发起审批"/>
                    <sequenceFlow id="flowToApprovalTask" sourceRef="startEvent" targetRef="approvalTask"/>
                    <userTask id="approvalTask" name="审批" flowable:assignee="${currentApprover}">
                      <extensionElements>
                        <flowable:taskListener event="complete" class="com.example.template.approval.engine.flowable.listener.ApprovalActionTaskListener"/>
                      </extensionElements>
                      <multiInstanceLoopCharacteristics isSequential="true" flowable:collection="approverList" flowable:elementVariable="currentApprover">
                        <completionCondition>${approvalAction == 'REJECT' || nrOfCompletedInstances == nrOfInstances}</completionCondition>
                      </multiInstanceLoopCharacteristics>
                    </userTask>
                    <sequenceFlow id="flowToEndEvent" sourceRef="approvalTask" targetRef="endEvent"/>
                    <endEvent id="endEvent" name="审批结束">
                      <extensionElements>
                        <flowable:executionListener event="end" class="com.example.template.approval.engine.flowable.listener.ApprovalResultExecutionListener"/>
                      </extensionElements>
                    </endEvent>
                  </process>
                </definitions>
                """.formatted(processKey, safeName);
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
