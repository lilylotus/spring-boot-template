package com.example.template.validation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 表单编码参数校验示例请求，演示文本非空、长度、邮箱格式和数值范围约束。
 *
 * @param username 用户名，长度必须在 2 到 20 个字符之间
 * @param email 邮箱地址，必须符合标准邮箱格式
 * @param age 年龄，必须在 18 到 120 之间
 */
@Schema(description = "表单编码参数校验示例请求")
public record ValidationFormRequest(
    @NotBlank(message = "username不能为空白")
    @Size(min = 2, max = 20, message = "username长度必须在2到20之间")
    @Schema(description = "用户名，长度为2到20个字符", example = "zhangsan", requiredMode = Schema.RequiredMode.REQUIRED)
    String username,

    @NotBlank(message = "email不能为空白")
    @Email(message = "email必须是合法邮箱")
    @Schema(description = "邮箱地址", example = "zhangsan@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    String email,

    @NotNull(message = "age不能为null")
    @Min(value = 18, message = "age不能小于18")
    @Max(value = 120, message = "age不能大于120")
    @Schema(description = "年龄，范围为18到120", example = "30", requiredMode = Schema.RequiredMode.REQUIRED)
    Integer age) {
}
