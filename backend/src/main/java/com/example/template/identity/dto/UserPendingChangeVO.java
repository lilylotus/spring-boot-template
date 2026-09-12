package com.example.template.identity.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户详情中展示的“待审批中的编辑变更”内容。
 */
@Data
public class UserPendingChangeVO {

    private Long id;

    private String status;

    private String newRealName;

    private String newMobile;

    private String newEmail;

    private LocalDateTime createdTime;
}
