## 1. 数据库与配置

- [x] 1.1 新增 Flyway 迁移脚本 `src/main/resources/db/migration/V1__create_file_upload_table.sql`，
      创建 `file_upload` 表（`id`/`original_name`/`stored_name`/`file_size`/`content_type`/
      `status`/`create_time`/`update_time`），注释风格参照 `src/main/resources/sql/outbox.sql`。
- [x] 1.2 在 `application.yml` 新增 `spring.servlet.multipart.max-file-size` /
      `max-request-size`（默认各 20MB）以及 `file.storage.base-dir`（默认 `upload`）配置，
      按现有风格补充中文注释说明用途。
- [x] 1.3 在 `.gitignore` 中新增忽略运行期生成的 `upload/` 目录的规则。

## 2. 领域模型与持久层

- [x] 2.1 新增 `com.example.template.file.entity.FileUpload`（`@TableName("file_upload")`
      实体），字段与迁移脚本一一对应，补充类注释与非平凡逻辑的方法注释。
- [x] 2.2 新增 `com.example.template.file.entity.FileStatus`（`NORMAL`/`DISABLED` 字符串常量类，
      参照 `OutboxEventStatus` 的写法和注释密度）。
- [x] 2.3 新增 `com.example.template.file.mapper.FileUploadMapper`（`extends BaseMapper<FileUpload>`
      + `@Mapper`，参照 `BatchTestPlusMapper`）。

## 3. 配置与异常处理

- [x] 3.1 新增 `com.example.template.file.config.FileStorageProperties`
      （`@ConfigurationProperties(prefix = "file.storage")`，字段 `baseDir`），并在文件模块的
      `@Configuration` 类上用 `@EnableConfigurationProperties(FileStorageProperties.class)`
      注册（参照 `RedisLockConfiguration`/`FeignHttpClientConfig` 的写法，本仓库未开启
      `@ConfigurationPropertiesScan`）。
- [x] 3.2 新增 `com.example.template.file.exception.FileStorageException`（携带 HTTP 状态码 +
      提示信息的未受检异常），并在 `GlobalExceptionHandler` 中新增对应的
      `@ExceptionHandler`，统一转换成 `RestResult` 错误响应。

## 4. 服务层

- [x] 4.1 新增 `com.example.template.file.service.FileStorageService`
      （接口）与实现类，实现上传逻辑：校验文件非空、大小限制、生成"随机字符串+原始后缀"存储
      文件名（后缀校验规则见 design.md）、确保存储根目录存在、落盘、落库；数据库写入失败时
      尽力删除已落盘的文件并记录警告日志。
- [x] 4.2 在服务层实现下载逻辑：按 id 查询记录，记录不存在抛 404 语义的
      `FileStorageException`，状态为 `DISABLED` 抛 410 语义的 `FileStorageException`，磁盘文件
      缺失抛 500 语义的 `FileStorageException` 并记录错误日志，否则返回可供控制器读取的文件
      资源与原始文件名。

## 5. 控制器

- [x] 5.1 新增 `com.example.template.file.controller.FileController`，实现
      `POST /api/files/upload`（`multipart/form-data`，字段名 `file`），返回
      `RestResult<FileUploadResponse>`（`id`/`originalName`/`size`），补充 springdoc
      `@Tag`/`@Operation` 注解（参照 `ValidationExampleController`）。
- [x] 5.2 实现 `GET /api/files/{id}/download`，返回 `ResponseEntity<Resource>`，
      `Content-Disposition` 使用 `ContentDisposition.builder("attachment")`
      按 UTF-8 编码原始文件名，避免中文文件名乱码。
- [x] 5.3 新增 `com.example.template.file.controller.FileUploadResponse`
      （上传接口响应体，含类/字段说明注释）。

## 6. 验证

- [x] 6.1 补充/运行必要的单元或集成测试，覆盖：上传成功返回 id、空文件被拒绝、超大文件被
      拒绝、正常状态文件下载成功、不存在的 id 下载返回 404、失效状态文件下载被拒绝返回 410。
      （超大文件被拒绝这一项由 `GlobalExceptionHandler` 的 `MaxUploadSizeExceededException`
      处理器承担，属于 Spring MVC 框架行为，未单独写测试覆盖）
- [x] 6.2 本地执行 `./gradlew build`（或至少 `./gradlew test --tests
      "com.example.template.file.*"`），确认新增代码编译通过、测试通过。
- [ ] 6.3 视需要用 `bootRun` 手动验证一次真实的 multipart 上传 + 下载流程（依赖本地
      MySQL/Redis/Nacos 基础设施，若不可用则在 PR 描述中注明未做手动验证）。
