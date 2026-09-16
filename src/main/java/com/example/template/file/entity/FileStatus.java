package com.example.template.file.entity;

/**
 * {@link FileUpload#getStatus()} 的取值常量：NORMAL(正常，可下载) / DISABLED(失效，禁止下载)。
 * 用普通字符串常量而不是枚举，与{@code OutboxEventStatus}保持相同做法，避免引入MyBatis-Plus的
 * 枚举类型处理器配置，保持与数据库列(VARCHAR)的映射足够简单直接。
 */
public final class FileStatus {

    /** 正常，允许下载。 */
    public static final String NORMAL = "NORMAL";

    /** 已失效，即使磁盘物理文件仍存在也一律拒绝下载。 */
    public static final String DISABLED = "DISABLED";

    private FileStatus() {
    }

}
