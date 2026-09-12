package com.example.template.approval.config.publish;

public interface ProcessDefinitionPublisher {
    PublishedDefinition deploy(String deploymentName, String resourceName, String bpmnXml);

    void deleteDeployment(String deploymentId);

    record PublishedDefinition(String deploymentId, String processDefinitionId, String processDefinitionKey) {
    }
}
