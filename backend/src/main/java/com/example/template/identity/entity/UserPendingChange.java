package com.example.template.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户编辑场景下的“待生效变更”快照记录：审批开启时，编辑请求先落到本表，不直接修改
 * {@link SysUser}，审批通过后才把 {@code afterSnapshot} 应用到目标用户。
 */
@Data
@TableName("user_pending_change")
public class UserPendingChange {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 变更前的用户字段快照（JSON 字符串）。 */
    private String beforeSnapshot;

    /** 变更后的用户字段快照（JSON 字符串）。 */
    private String afterSnapshot;

    /** PENDING / APPROVED / REJECTED，取值见 {@link com.example.template.identity.enums.PendingChangeStatus}。 */
    private String status;

    private String operatorId;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
