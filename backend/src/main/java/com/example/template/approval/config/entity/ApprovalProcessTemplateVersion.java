package com.example.template.approval.config.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("approval_process_template_version")
public class ApprovalProcessTemplateVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private Integer versionNo;
    private String modelJson;
    private String bpmnXml;
    private String deploymentId;
    private String processDefinitionId;
    private String processDefinitionKey;
    private LocalDateTime publishedTime;
}
