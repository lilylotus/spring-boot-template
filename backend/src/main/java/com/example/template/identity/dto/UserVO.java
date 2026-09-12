package com.example.template.identity.dto;

import lombok.Data;

/**
 * 用户列表/详情的展示对象：始终展示已生效信息 + 当前状态；详情场景下若存在待审批中的
 * 编辑变更，一并通过 {@link #pendingChange} 返回。
 */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String realName;

    private String mobile;

    private String email;

    /** PENDING / ACTIVE / REJECTED。 */
    private String status;

    /** 待审批中的编辑变更内容，不存在时为 {@code null}。 */
    private UserPendingChangeVO pendingChange;
}
