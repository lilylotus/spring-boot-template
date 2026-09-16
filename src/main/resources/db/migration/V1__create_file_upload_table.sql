-- Flyway迁移脚本，由spring.flyway在应用启动时自动执行(见application.yml)。
-- 本文件是本项目第一份Flyway迁移脚本，版本号从V1开始。

-- 通用文件上传下载功能的文件记录表：一次成功上传对应一条记录，下载接口按id查询本表定位磁盘文件
CREATE TABLE file_upload (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    original_name VARCHAR(255) NOT NULL,  -- 原始文件名(调用方上传时提交的文件名，含后缀)，下载时作为响应文件名
    stored_name   VARCHAR(255) NOT NULL,  -- 磁盘存储文件名(随机字符串+原始后缀)，实际物理路径=存储根目录+stored_name
    file_size     BIGINT NOT NULL,        -- 文件大小(字节)
    content_type  VARCHAR(128),           -- 上传时的MIME类型，可能为空
    status        VARCHAR(16) NOT NULL,   -- NORMAL(正常，可下载) / DISABLED(失效，禁止下载)
    create_time   DATETIME NOT NULL,
    update_time   DATETIME NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
