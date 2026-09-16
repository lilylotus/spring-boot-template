## Why

当前模板仓库没有通用的文件上传/下载能力，各业务模块如果需要保存附件（如请假审批凭证、导入导出文件等），
只能各自实现一套存储逻辑。补充一个通用、可直接复用的文件上传下载模块，作为模板的标准示例之一。

## What Changes

- 新增文件上传接口：接收 multipart 文件，以"随机字符串 + 原始文件后缀"生成磁盘存储文件名，默认保存到项目
  根目录下的 `upload` 目录（目录不存在时自动创建），并将文件的元信息写入新增的文件上传记录表。
- 新增文件下载接口：按文件记录表主键 id 下载文件；仅"正常"状态的记录允许下载，"失效"状态的记录拒绝下载
  并返回明确的错误提示。
- 新增文件上传记录表（Flyway 迁移脚本，路径 `src/main/resources/db/migration`），字段至少包含：原始文件名、
  磁盘存储文件名、文件状态（正常/失效）等下载接口判断状态所需的信息。
- 新增文件存储路径、单文件大小上限等可配置项（`application.yml`），默认值满足开箱即用。

## Capabilities

### New Capabilities
- `file-storage`: 通用文件上传、下载能力，包括文件落盘存储策略、文件元信息记录表、按 id 下载及状态校验规则。

### Modified Capabilities
(无，本次不涉及已有 spec 的需求变更)

## Impact

- 新增 Java 包 `com.example.template.file`（entity/mapper/service/controller），复用现有
  `RestResult`/`RestResultUtils` 统一响应结构与 `GlobalExceptionHandler` 异常处理约定。
- 新增数据库表 `file_upload`，通过 Flyway 迁移脚本创建（`spring.flyway` 已在 `application.yml` 中启用，
  迁移目录 `src/main/resources/db/migration` 当前为空，本次是第一份迁移脚本）。
- 新增/调整 `application.yml` 中的 `spring.servlet.multipart` 配置及自定义 `file.storage` 配置节。
- 运行期在项目工作目录下新增 `upload/` 目录用于存放上传文件（不纳入版本库，需要补充 `.gitignore` 规则）。
