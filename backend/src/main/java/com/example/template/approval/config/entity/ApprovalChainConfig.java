package com.example.template.approval.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指定用户多级审批链配置：按 {@code bizType} 分别维护，{@code (bizType, levelNo)} 唯一。
 */
@Data
@TableName("approval_chain_config")
public class ApprovalChainConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizType;

    private Integer levelNo;

    private String approverUserId;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
