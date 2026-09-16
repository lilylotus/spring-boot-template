package com.example.template.file.service;

import com.example.template.file.config.FileStorageProperties;
import com.example.template.file.entity.FileStatus;
import com.example.template.file.entity.FileUpload;
import com.example.template.file.exception.FileStorageException;
import com.example.template.file.mapper.FileUploadMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 基于本地磁盘的文件存储服务实现：文件写入{@link FileStorageProperties#getBaseDir()}指向的目录，
 * 元信息通过{@link FileUploadMapper}落库。类名带上"Local"前缀，便于将来替换成对象存储实现时
 * 与本实现并存、按需切换。
 */
@Service
public class LocalFileStorageService implements FileStorageService {

    private static final Logger LOGGER = LogManager.getLogger(LocalFileStorageService.class);

    /** 磁盘存储文件名的后缀只允许字母数字、且不超过20个字符，防止调用方提交的原始文件名污染物理路径。 */
    private static final Pattern SAFE_EXTENSION_PATTERN = Pattern.compile("[A-Za-z0-9]{1,20}");

    private final FileUploadMapper fileUploadMapper;
    private final Path baseDir;

    /**
     * 构造函数中直接把配置的存储根目录解析成绝对路径并确保目录存在，让"目录不可用"这类问题在
     * 应用启动阶段暴露，而不是拖到第一次上传请求时才失败。
     *
     * @param fileUploadMapper 文件记录表的Mapper
     * @param properties       文件存储配置
     */
    public LocalFileStorageService(FileUploadMapper fileUploadMapper, FileStorageProperties properties) {
        this.fileUploadMapper = fileUploadMapper;
        this.baseDir = Paths.get(properties.getBaseDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建文件存储目录: " + this.baseDir, e);
        }
    }

    /**
     * 上传处理顺序：先落盘再落库；数据库写入失败时尽力删除刚写入的磁盘文件，避免留下
     * "有物理文件但无记录"的孤儿文件（删除失败只记录警告日志，不影响原始异常继续向上抛出）。
     *
     * @param file 调用方提交的multipart文件
     * @return 已写入数据库的文件记录
     */
    @Override
    public FileUpload upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileStorageException(HttpStatus.BAD_REQUEST, "上传文件不能为空");
        }

        String originalName = StringUtils.hasText(file.getOriginalFilename())
            ? file.getOriginalFilename() : file.getName();
        String storedName = generateStoredName(originalName);
        Path target = baseDir.resolve(storedName);
        try {
            file.transferTo(target);
        } catch (IOException e) {
            throw new UncheckedIOException("文件保存失败: " + target, e);
        }

        LocalDateTime now = LocalDateTime.now();
        FileUpload record = new FileUpload();
        record.setOriginalName(originalName);
        record.setStoredName(storedName);
        record.setFileSize(file.getSize());
        record.setContentType(file.getContentType());
        record.setStatus(FileStatus.NORMAL);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        try {
            fileUploadMapper.insert(record);
        } catch (RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
        return record;
    }

    /**
     * 依次校验记录是否存在、状态是否正常、磁盘文件是否仍在，三项都通过才返回可下载的资源。
     *
     * @param id 文件记录主键id
     * @return 下载所需的文件资源与原始文件名
     */
    @Override
    public FileDownloadResult prepareDownload(Long id) {
        FileUpload record = fileUploadMapper.selectById(id);
        if (record == null) {
            throw new FileStorageException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        if (FileStatus.DISABLED.equals(record.getStatus())) {
            throw new FileStorageException(HttpStatus.GONE, "文件已失效，无法下载");
        }

        Path target = baseDir.resolve(record.getStoredName());
        if (!Files.exists(target)) {
            LOGGER.error("文件记录存在但磁盘文件缺失，id={}, storedName={}", id, record.getStoredName());
            throw new FileStorageException(HttpStatus.INTERNAL_SERVER_ERROR, "文件不存在于存储介质");
        }

        Resource resource = new FileSystemResource(target);
        return new FileDownloadResult(resource, record.getOriginalName(), record.getContentType());
    }

    /**
     * 生成磁盘存储文件名：随机字符串(UUID去掉短横线) + 原始文件名中提取出的合法后缀(可能为空)。
     *
     * @param originalName 原始文件名
     * @return 磁盘存储文件名
     */
    private String generateStoredName(String originalName) {
        String extension = extractSafeExtension(originalName);
        String random = UUID.randomUUID().toString().replace("-", "");
        return extension.isEmpty() ? random : random + "." + extension;
    }

    /**
     * 从原始文件名中提取"安全"的后缀：先只取最后一段(丢弃调用方可能夹带的路径分隔符)，再要求
     * 后缀本身只由字母数字组成且长度可控；不满足任一条件都当作没有后缀处理，避免用户输入影响
     * 落盘路径或引入非预期字符。
     *
     * @param originalName 原始文件名
     * @return 合法后缀(不含'.')，没有则返回空字符串
     */
    private String extractSafeExtension(String originalName) {
        String fileName = originalName;
        int lastSeparator = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            fileName = fileName.substring(lastSeparator + 1);
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dotIndex + 1);
        return SAFE_EXTENSION_PATTERN.matcher(extension).matches()
            ? extension.toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 尽力删除孤儿文件，删除失败只记录警告日志，不影响调用方原本要抛出的异常。
     *
     * @param target 待删除的磁盘文件路径
     */
    private void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            LOGGER.warn("清理孤儿文件失败: {}", target, e);
        }
    }
}
