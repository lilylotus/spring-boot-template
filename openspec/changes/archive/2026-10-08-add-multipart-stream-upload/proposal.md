# Proposal

## Why

`MultipartRequestBody` 目前只能通过 `MultipartFilePart`（基于 `java.nio.file.Path`）提供文件字段，要求调用方必须先把待上传内容落地成本地文件。当内容来自内存、网络下载、压缩/加密中间结果等非本地文件来源时，调用方被迫先写临时文件再上传，增加了不必要的 I/O 和临时文件清理负担。需要让 `MultipartRequestBody` 支持直接传入 `InputStream` 作为文件字段来源。

## What Changes

- 新增 `org.example.simple.http.MultipartStreamPart` 记录类型，携带 `fieldName`、`inputStream`、`fileName`、`contentType` 四个字段，作为基于流的 multipart 文件字段描述（与现有基于 `Path` 的 `MultipartFilePart` 并列的另一种文件字段来源）。
- 为 `MultipartRequestBody` 新增 `streamParts` 字段（`List<MultipartStreamPart>`），画出一个新的三参数规范构造器 `MultipartRequestBody(textFields, fileParts, streamParts)`；同时保留现有两参数构造方式 `MultipartRequestBody(textFields, fileParts)`（内部委托为 `streamParts` 传入空列表），确保现有调用方（如 `HttpClientsTest`、`HttpModelsTest`）不需要修改即可继续编译通过。
- `MultipartRequestBody` 的“至少一个字段”校验从“文本字段或文件字段非空”扩展为“文本字段、文件字段或流字段三者至少一个非空”。
- `HttpClients` 执行 multipart 请求时，除现有基于 `Path` 的二进制字段外，一并把 `streamParts` 中的每个 `MultipartStreamPart` 添加为 multipart 请求体的二进制字段。
- **BREAKING（资源所有权约定，非方法签名变更）**：调用方将 `InputStream` 传入 `MultipartStreamPart` 后即转移该流的所有权给 `HttpClients`；`HttpClients` 在请求发送完成后（无论成功、失败或超时）负责关闭该流，调用方不应再手动关闭、也不应在传入后继续读取该流。此约定仅适用于新增的流式文件字段，不影响现有基于 `Path` 的 `MultipartFilePart` 行为。

## Capabilities

### New Capabilities

无（本变更是对现有 `http-client-utility` 能力的扩展，不引入独立新能力）。

### Modified Capabilities

- `http-client-utility`: “支持多种请求体类型”需求新增“multipart 文件字段可以直接来自 `InputStream` 而不要求先落地为本地文件；该场景下 `HttpClients` 负责在请求结束后关闭调用方传入的流”的场景。

## Impact

- 新增 `src/main/java/org/example/simple/http/MultipartStreamPart.java`。
- 新增 `src/test/java/org/example/simple/http/MultipartStreamPartTest.java`。
- 修改 `src/main/java/org/example/simple/http/MultipartRequestBody.java`：新增 `streamParts` 组件和兼容性两参数构造器。
- 修改 `src/main/java/org/example/simple/http/HttpClients.java`：`ClientRuntime.createMultipartEntity` 增加对 `streamParts` 的处理，并在请求结束后关闭已消费的流。
- 修改/新增相关测试：`HttpModelsTest`（`MultipartRequestBody` 校验与兼容构造器）、`HttpClientsTest`（端到端通过流上传文件并验证流被关闭）。
- 不新增 Gradle 依赖；不修改现有基于 `Path` 的 `MultipartFilePart` 的字段、方法签名或校验逻辑。
