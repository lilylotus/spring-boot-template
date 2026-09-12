package com.example.template.approval.config.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批全局开关（单行总开关，固定 id = 1）。
 */
@Data
@TableName("approval_switch")
public class ApprovalSwitch {

    /** 固定为 1 的单行记录主键。 */
    public static final Long SINGLETON_ID = 1L;

    @TableId
    private Long id;

    private Boolean approvalEnabled;

    private LocalDateTime updatedTime;
}
