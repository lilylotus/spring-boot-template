package com.example.template.approval.config.service;

import com.example.template.approval.config.dto.PublishedProcessTemplate;

public interface PublishedProcessResolver {
    PublishedProcessTemplate resolve(String bizType, String scope);
}
