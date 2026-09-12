package com.example.template.approval.config.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("approval_process_template")
public class ApprovalProcessTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bizType;
    private String scopeKey;
    private String name;
    private String draftModelJson;
    private Long draftRevision;
    private Long activeVersionId;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
