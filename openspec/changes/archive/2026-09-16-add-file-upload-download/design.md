## Context

模板仓库目前没有文件存储相关代码，`db/migration` 目录也还不存在——但 `application.yml`（当前未
提交的改动）已经启用了 `spring.flyway`（`locations: classpath:db/migration`），说明后续新增表结构
要用 Flyway 迁移脚本管理，而不是延续 `outbox.sql`/`batch-test.sql` 那种"手动执行的参考脚本"旧模式。
本次改动是第一份 Flyway 迁移脚本，文件号从 `V1` 开始。

其余可复用的既有约定：
- 统一响应结构 `RestResult`/`RestResultUtils`，控制器一律返回 `RestResult<T>`（下载接口的成功路径
  例外，直接返回文件二进制流）。
- `GlobalExceptionHandler` 集中把异常映射成 `RestResult`，客户端可预期的错误保持 4xx、未预期异常
  统一 500。
- 自定义配置项通过 `@ConfigurationProperties` 类承载（参考 `HttpClientProperties`、
  `RedisLockProperties`），而不是散落的 `@Value`。
- MyBatis-Plus 的 `BaseMapper<T>` + `@Mapper` 足以覆盖本次单表 CRUD，不需要手写 XML（参考
  `BatchTestPlusMapper`）。

## Goals / Non-Goals

**Goals:**
- 提供一个通用、与具体业务无关的文件上传/下载接口，可直接被模板衍生出的真实服务复用。
- 上传文件以"随机字符串 + 原始后缀名"落盘，元信息（原始文件名、存储文件名、状态等）落库。
- 下载按文件记录表主键 id 进行，只有"正常"状态的记录可以下载。

**Non-Goals:**
- 不做分片上传/断点续传、秒传（内容哈希去重）、文件预览/图片压缩等增强能力。
- 不做多存储后端抽象（如对接对象存储 OSS/S3）；本次只实现本地磁盘存储，目录可配置。
- 不做文件访问权限/鉴权体系（谁能上传、谁能下载哪些文件）；这属于业务系统自己接入
  认证鉴权后要做的事，模板只演示存取机制本身。
- 不提供"失效文件"的物理删除/清理任务；状态失效只影响下载接口是否放行，磁盘文件是否
  同步清理留给具体业务决定。

## Decisions

### 表结构与迁移方式
新增表 `file_upload`，通过 Flyway 迁移脚本
`src/main/resources/db/migration/V1__create_file_upload_table.sql` 创建：

| 列名           | 类型           | 说明                                             |
|----------------|----------------|--------------------------------------------------|
| id             | BIGINT AUTO_INCREMENT PK | 主键，即下载接口使用的文件 id                |
| original_name  | VARCHAR(255) NOT NULL    | 原始文件名（调用方上传时提交的文件名，含后缀）|
| stored_name    | VARCHAR(255) NOT NULL    | 磁盘存储文件名（随机字符串+原始后缀），实际路径 = 存储根目录 + stored_name |
| file_size      | BIGINT NOT NULL          | 文件大小（字节）                              |
| content_type   | VARCHAR(128)             | 上传时的 MIME 类型，可能为空                  |
| status         | VARCHAR(16) NOT NULL     | NORMAL（正常，可下载）/ DISABLED（失效，禁止下载）|
| create_time    | DATETIME NOT NULL        | 创建时间                                       |
| update_time    | DATETIME NOT NULL        | 更新时间                                       |

理由：只按 id 精确查询下载，不需要额外索引；`status` 用字符串常量而不是枚举，延续
`OutboxEventStatus` 的做法，避免引入 MyBatis-Plus 枚举类型处理器配置。

### 存储文件命名与目录
- 磁盘存储文件名 = `UUID去掉短横线的32位十六进制字符串` + （若原始文件名存在合法后缀则加 `.` + 后缀）。
- 后缀提取前先取原始文件名的最后一段（丢弃调用方传入文件名里可能包含的路径分隔符），
  只允许字母、数字组成、长度 ≤ 20 的后缀；不满足则视为无后缀，避免调用方通过精心构造的
  文件名（如包含 `..`、路径分隔符或超长字符串）影响落盘路径或引入非预期字符。
- 存储文件名完全由服务端生成、不拼接任何用户可控片段进最终物理路径，从根本上规避路径穿越风险，
  不需要额外的路径校验逻辑。
- 存储根目录通过 `file.storage.base-dir` 配置，默认值 `upload`（相对路径，相对 JVM 工作目录，
  即项目根目录下的 `upload/`）；应用启动时若目录不存在则自动创建。
- 新增 `.gitignore` 规则忽略 `upload/` 目录，避免运行期产生的文件被误提交。

### 配置项
新增 `FileStorageProperties`（`@ConfigurationProperties(prefix = "file.storage")`），字段：
- `baseDir`：存储根目录，默认 `upload`。

文件大小上限复用 Spring Boot 原生 `spring.servlet.multipart.max-file-size` /
`max-request-size` 配置（`application.yml` 新增，默认各设为 20MB），不重复造轮子。

### 接口设计
- `POST /api/files/upload`：`multipart/form-data`，文件字段名 `file`；成功返回
  `RestResult<FileUploadResponse>`（`id`、`originalName`、`size`）。空文件在服务层显式校验后
  抛 `FileStorageException` 返回 400；超出大小限制则由 Spring MVC 在解析 multipart 请求阶段
  直接抛出 `MaxUploadSizeExceededException`，请求根本不会进入 Controller/Service 代码，
  由 `GlobalExceptionHandler` 新增的对应 `@ExceptionHandler` 统一转换成 413 的 `RestResult`
  响应（详见下方"错误处理"）。
- `GET /api/files/{id}/download`：路径参数 `id` 对应文件记录主键；成功时直接返回文件二进制流
  （`ResponseEntity<Resource>`），`Content-Disposition` 用记录中的 `originalName`（走
  `ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8)`，避免中文
  文件名乱码）；响应的 `Content-Type` 按记录中保存的 MIME 类型解析，解析失败或为空时退化成
  `application/octet-stream`。记录不存在、状态为失效、磁盘文件缺失三种情况都返回统一的 `RestResult` 错误
  响应（而不是裸 404 页面），分别对应 404（不存在）、410 Gone（已失效）、500（元数据与磁盘
  不一致，视为未预期的内部错误，需要人工排查）。

### 错误处理
新增一个轻量级未受检异常 `FileStorageException`（携带 HTTP 状态码 + 提示信息），在
`GlobalExceptionHandler` 中新增一个 `@ExceptionHandler(FileStorageException.class)`，统一转换成
对应状态码的 `RestResult`。上传/下载服务方法中"文件不存在"（404）"文件已失效"（410）"空文件"
（400）"磁盘文件缺失"（500）这几种可预期的业务错误都抛这个异常。

"超出大小限制"不在服务层校验、也不通过 `FileStorageException` 表达：它由 Spring MVC 在解析
multipart 请求参数阶段直接抛出框架自带的 `MaxUploadSizeExceededException`，早于 Controller 方法
体执行，因此在 `GlobalExceptionHandler` 中单独新增一个
`@ExceptionHandler(MaxUploadSizeExceededException.class)`，转换成 413 的 `RestResult`，不复用
`FileStorageException`。

未预期的 `IOException` 等继续交给兜底的 `handleUnexpectedException` 处理（返回 500，日志记录
完整堆栈）。

### 落盘与落库的一致性
上传处理顺序：先写磁盘文件，再插入数据库记录；数据库插入失败时，捕获异常后尝试删除刚写入的
磁盘文件（尽力清理，删除失败只记录警告日志不影响异常继续抛出），避免留下"有物理文件但无记录"
的孤儿文件。不引入分布式事务/两阶段提交，这在模板演示场景下是可接受的简化。

## Risks / Trade-offs

- [磁盘文件与数据库记录不一致（如磁盘文件被外部误删，记录仍是 NORMAL）] → 下载时按需检测文件是否
  存在，不存在则返回明确的 500 错误并记录日志，暴露问题而不是静默失败；不做后台一致性巡检
  （超出本次范围）。
- [本地磁盘存储不支持多实例部署间共享文件] → 属于已知 Non-Goal（不做对象存储抽象），文档中
  注明这是单机模板演示，多实例场景需要自行替换为共享存储。
- [大文件上传占用内存] → Spring MVC 的 `MultipartFile` 默认基于临时文件而非整体驻留内存（超过
  阈值后落临时磁盘文件），配合 `max-file-size` 限制，风险可控。

## Migration Plan

- 新增 Flyway 迁移脚本随下次发布自动建表，`baseline-on-migrate: true` 保证已有环境不受影响。
- 无需数据迁移（全新表、全新功能）。
- 回滚方式：如需回滚代码，Flyway 已应用的建表迁移保留即可（空表不影响回滚后的旧版本运行）；
  如确需回滚表结构，手工执行 `DROP TABLE file_upload` 并在 Flyway 历史表中处理对应记录。
