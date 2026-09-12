package com.example.template.identity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户可编辑字段的快照，用于 {@code user_pending_change} 的 before/after 落库，
 * 并作为 {@link UserApprovalPayloadSnapshot} 的前后值组成审批业务数据快照。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserFieldsSnapshot {

    private String username;

    private String realName;

    private String mobile;

    private String email;
}
