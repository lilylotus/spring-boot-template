package com.example.template.approval.config.design;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class ProcessDesignModel {
    private Integer modelVersion = 1;
    private List<ProcessDesignNode> nodes = new ArrayList<>();
    private List<ProcessDesignEdge> edges = new ArrayList<>();
}
