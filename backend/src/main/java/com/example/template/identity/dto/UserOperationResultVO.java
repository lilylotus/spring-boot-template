package com.example.template.identity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 新增/编辑用户操作的响应：明确告知调用方本次是“已生效”还是“待审批”。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserOperationResultVO {

    /** 用户ID。 */
    private Long userId;

    /** 本次操作后用户/变更所处的状态（PENDING/ACTIVE）。 */
    private String status;

    /** 是否已直接生效（true=已生效，false=待审批）。 */
    private boolean effective;

    /** 面向前端展示的提示信息。 */
    private String message;
}
