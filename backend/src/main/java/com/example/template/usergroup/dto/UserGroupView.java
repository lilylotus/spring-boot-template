package com.example.template.usergroup.dto;

import java.util.List;

/** 用户组页面与审批配置共用视图，区分全部成员与当前有效成员。 */
public record UserGroupView(Long id, String code, String name, String description, String status,
                            Long revision, List<Long> memberIds, List<String> activeMemberIds) {
}
