package com.example.template.usergroup.entity;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** 用户组持久化信息，由用户组服务维护；不存储审批实例或成员快照。 */
@Data
@TableName("sys_user_group")
public class UserGroup {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String description;
    private String status;
    private Long revision;
    private String createdBy;
    private LocalDateTime createdTime;
    private String updatedBy;
    private LocalDateTime updatedTime;
}
