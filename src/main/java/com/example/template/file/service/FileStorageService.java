package com.example.template.file.service;

import com.example.template.file.entity.FileUpload;
import org.springframework.web.multipart.MultipartFile;

/**
 * 通用文件上传下载服务，负责文件落盘/落库以及按记录id定位可下载的文件。
 */
public interface FileStorageService {

    /**
     * 保存一次上传：文件落盘，元信息落库。
     *
     * @param file 调用方提交的multipart文件，不能为空
     * @return 已写入数据库的文件记录（含数据库生成的主键id）
     */
    FileUpload upload(MultipartFile file);

    /**
     * 按文件记录主键id准备一次下载，只有状态为正常的记录才会返回结果。
     *
     * @param id 文件记录主键id
     * @return 下载所需的文件资源与原始文件名
     */
    FileDownloadResult prepareDownload(Long id);
}
