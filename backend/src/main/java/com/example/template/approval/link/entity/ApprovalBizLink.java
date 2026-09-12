package com.example.template.approval.link.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 业务单据 <-> Flowable 流程实例 的关联记录。{@code identity} 等业务模块不直接持有该表，
 * 只能通过 {@link com.example.template.approval.api.ApprovalGateway} 间接查询。
 */
@Data
@TableName("approval_biz_link")
public class ApprovalBizLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizType;

    private String bizId;

    private String processInstanceId;

    private String businessKey;

    private Long templateId;

    private String templateName;

    private Long templateVersionId;

    private Integer templateVersionNo;

    private String processDefinitionId;

    /** PENDING / APPROVED / REJECTED，取值见 {@link com.example.template.approval.api.dto.ApprovalStatus}。 */
    private String status;

    private String initiatorUserId;

    private String approverSnapshot;

    private String payloadSnapshot;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
