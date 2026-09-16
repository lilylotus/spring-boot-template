package com.example.template.file.service;

import org.springframework.core.io.Resource;

/**
 * 下载服务的返回结果，携带Controller构造HTTP响应所需的文件资源、原始文件名和MIME类型。
 *
 * @param resource     磁盘上的文件资源
 * @param originalName 原始文件名，用于Content-Disposition
 * @param contentType  上传时记录的MIME类型，可能为null
 */
public record FileDownloadResult(Resource resource, String originalName, String contentType) {
}
