package com.example.template.approval.engine.flowable;

import lombok.RequiredArgsConstructor;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;

import com.example.template.approval.config.publish.ProcessDefinitionPublisher;
import com.example.template.common.BusinessException;

@Service
@RequiredArgsConstructor
public class FlowableProcessDefinitionPublisher implements ProcessDefinitionPublisher {
    private final RepositoryService repositoryService;

    @Override
    public PublishedDefinition deploy(String deploymentName, String resourceName, String bpmnXml) {
        Deployment deployment = repositoryService.createDeployment()
                .name(deploymentName)
                .addString(resourceName, bpmnXml)
                .deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .singleResult();
        if (definition == null) {
            repositoryService.deleteDeployment(deployment.getId(), true);
            throw new BusinessException("流程部署后未找到流程定义");
        }
        return new PublishedDefinition(deployment.getId(), definition.getId(), definition.getKey());
    }

    @Override
    public void deleteDeployment(String deploymentId) {
        repositoryService.deleteDeployment(deploymentId, true);
    }
}
