package com.example.template.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户主表。不存任何 Flowable 相关列（不存 processInstanceId/taskId），
 * 审批进度一律经 {@code approval.api.ApprovalGateway} 查询。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String realName;

    private String mobile;

    private String email;

    /** PENDING / ACTIVE / REJECTED，取值见 {@link com.example.template.identity.enums.UserStatus}。 */
    private String status;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
