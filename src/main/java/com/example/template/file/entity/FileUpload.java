package com.example.template.file.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 通用文件上传记录表对应的实体，一次成功上传对应一条记录；下载接口按{@link #id}查询本记录，
 * 结合{@link #status}判断能否下载，再用{@link #storedName}定位磁盘上的物理文件。
 */
@TableName("file_upload")
public class FileUpload {

    /** 主键沿用全局配置的id-type: auto(见application.yml的mybatis-plus.global-config)。 */
    @TableId
    private Long id;

    /** 原始文件名(调用方上传时提交的文件名，含后缀)，下载时作为响应的文件名。 */
    private String originalName;

    /** 磁盘存储文件名(随机字符串+原始后缀)，实际物理路径=存储根目录+本字段，与originalName解耦。 */
    private String storedName;

    /** 文件大小(字节)。 */
    private Long fileSize;

    /** 上传时的MIME类型，可能为null。 */
    private String contentType;

    /** {@link FileStatus} 中的一个取值。 */
    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getStoredName() {
        return storedName;
    }

    public void setStoredName(String storedName) {
        this.storedName = storedName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
