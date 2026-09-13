package com.example.template.approval.config.design;

import lombok.Data;

/** 画布节点配置，用户组只保存组ID；最新成员由运行时查询。 */
@Data
public class ProcessDesignNode {
    private String id;
    private String type;
    private Double x;
    private Double y;
    private String approverUserId;
    private String assigneeType;
    private Long approverGroupId;
}
