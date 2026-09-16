package com.example.template.file.controller;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 文件上传接口的响应体，携带下载时需要用到的文件记录主键id。
 *
 * @param id           文件记录主键id，下载接口据此定位文件
 * @param originalName 原始文件名
 * @param size         文件大小(字节)
 */
@Schema(description = "文件上传响应")
public record FileUploadResponse(
    @Schema(description = "文件记录主键id，下载时使用", example = "1")
    Long id,
    @Schema(description = "原始文件名", example = "report.pdf")
    String originalName,
    @Schema(description = "文件大小(字节)", example = "10240")
    Long size) {
}
