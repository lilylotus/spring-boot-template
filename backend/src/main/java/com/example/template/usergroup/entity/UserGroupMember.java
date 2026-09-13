package com.example.template.usergroup.entity;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** 用户组与用户的当前成员关系，由成员维护事务整体替换。 */
@Data
@TableName("sys_user_group_member")
public class UserGroupMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long groupId;
    private Long userId;
    private String createdBy;
    private LocalDateTime createdTime;
    private String updatedBy;
    private LocalDateTime updatedTime;
}
