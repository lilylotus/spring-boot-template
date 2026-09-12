package com.example.template.approval.config.design;

import lombok.Data;

@Data
public class ProcessDesignNode {
    private String id;
    private String type;
    private Double x;
    private Double y;
    private String approverUserId;
}
