package com.example.template.approval.record.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 审批动作审计实体，稳定保存审批人对单个 Flowable 任务作出的处理结果。
 */
@Data
@TableName("approval_action_record")
public class ApprovalActionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizType;

    private String bizId;

    private String processInstanceId;

    private String taskId;

    private String approverUserId;
    private Long groupId;
    private String groupName;

    private Integer levelNo;

    private String taskTitle;

    private String action;

    private String comment;

    private LocalDateTime taskCreatedTime;

    private LocalDateTime actedTime;
}
