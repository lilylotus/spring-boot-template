package com.example.template.file.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 通用文件上传下载功能的配置入口，激活{@link FileStorageProperties}的属性绑定
 * (本仓库未开启{@code @ConfigurationPropertiesScan}，需要显式声明)。
 */
@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileStorageConfig {

}
