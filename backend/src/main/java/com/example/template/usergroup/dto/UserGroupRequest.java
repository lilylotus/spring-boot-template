package com.example.template.usergroup.dto;

import java.util.List;
import jakarta.validation.constraints.*;

/** 用户组及成员整体保存请求；revision 防止并发编辑覆盖。 */
public record UserGroupRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 500) String description,
        @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status,
        @NotNull @PositiveOrZero Long revision,
        @NotNull @Size(max = 500) List<@NotNull @Positive Long> memberIds) {
}
