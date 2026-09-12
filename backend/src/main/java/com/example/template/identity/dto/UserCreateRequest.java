package com.example.template.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新增用户请求。
 */
@Data
public class UserCreateRequest {

    @NotBlank(message = "账号不能为空")
    private String username;

    @NotBlank(message = "姓名不能为空")
    private String realName;

    private String mobile;

    @Email(message = "邮箱格式不正确")
    private String email;
}
