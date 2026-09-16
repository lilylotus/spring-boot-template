package com.example.template.file.controller;

import com.example.template.file.entity.FileUpload;
import com.example.template.file.service.FileDownloadResult;
import com.example.template.file.service.FileStorageService;
import com.example.template.response.RestResult;
import com.example.template.response.RestResultUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 通用文件上传下载示例接口，演示如何配合{@code FileStorageService}把文件落盘、落库，
 * 并按文件记录主键id提供下载。
 */
@RestController
@Tag(name = "文件上传下载示例接口", description = "演示通用文件上传/下载能力")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * 接收一个multipart文件并保存，返回文件记录主键id供后续下载使用。
     *
     * @param file 调用方提交的multipart文件，字段名固定为file
     * @return 包含文件记录id的统一成功响应
     */
    @PostMapping(value = "/api/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文件", description = "保存文件到本地磁盘并记录元信息，返回下载时使用的文件id")
    public RestResult<FileUploadResponse> upload(
        @Parameter(description = "待上传的文件") @RequestParam("file") MultipartFile file) {
        FileUpload saved = fileStorageService.upload(file);
        return RestResultUtils.success(
            new FileUploadResponse(saved.getId(), saved.getOriginalName(), saved.getFileSize()));
    }

    /**
     * 按文件记录主键id下载文件，只有状态正常的记录允许下载。
     *
     * @param id 文件记录主键id
     * @return 文件二进制内容，响应头携带原始文件名
     */
    @GetMapping("/api/files/{id}/download")
    @Operation(summary = "下载文件", description = "按文件记录id下载，失效状态的记录拒绝下载")
    public ResponseEntity<Resource> download(
        @Parameter(description = "文件记录主键id") @PathVariable Long id) {
        FileDownloadResult result = fileStorageService.prepareDownload(id);
        ContentDisposition contentDisposition = ContentDisposition.attachment()
            .filename(result.originalName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .contentType(resolveMediaType(result.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
            .body(result.resource());
    }

    /**
     * 把记录中保存的MIME类型字符串解析成{@link MediaType}；缺失或格式不合法时退化成通用二进制流，
     * 避免因为一条脏数据导致下载接口直接抛异常。
     *
     * @param contentType 记录中保存的MIME类型，可能为null
     * @return 解析后的响应媒体类型
     */
    private MediaType resolveMediaType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
