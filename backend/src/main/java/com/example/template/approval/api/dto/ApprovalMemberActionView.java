package com.example.template.approval.api.dto;

import java.time.LocalDateTime;

/** 用户组节点的一次真实成员投票，用于历史审计，成员移出后仍保留。 */
public record ApprovalMemberActionView(String userId, String action, String comment,
                                       LocalDateTime actedTime, String groupName) {
}
