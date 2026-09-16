package com.example.template.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通用文件上传下载功能的存储配置，对应{@code application.yml}中的{@code file.storage}节点。
 */
@ConfigurationProperties(prefix = "file.storage")
public class FileStorageProperties {

    /** 文件落盘的存储根目录，相对路径相对JVM工作目录解析；默认upload，启动时目录不存在会自动创建。 */
    private String baseDir = "upload";

    public String getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }
}
