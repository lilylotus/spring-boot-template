package com.example.template.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 编辑用户请求。账号不可编辑，只编辑姓名/手机号/邮箱等基础信息。
 */
@Data
public class UserUpdateRequest {

    @NotBlank(message = "姓名不能为空")
    private String realName;

    private String mobile;

    @Email(message = "邮箱格式不正确")
    private String email;
}
