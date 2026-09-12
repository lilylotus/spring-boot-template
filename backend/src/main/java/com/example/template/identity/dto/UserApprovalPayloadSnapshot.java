package com.example.template.identity.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 用户新增或编辑审批的版本化业务数据快照。
 *
 * @param before 修改前已生效数据；新增用户时为空
 * @param after  申请创建或修改后的数据
 */
public record UserApprovalPayloadSnapshot(
        @JsonInclude(JsonInclude.Include.ALWAYS) UserFieldsSnapshot before,
        UserFieldsSnapshot after) {
}
