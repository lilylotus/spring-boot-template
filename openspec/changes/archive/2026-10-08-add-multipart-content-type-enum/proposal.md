# Proposal

## Why

`org.example.simple.http.MultipartFilePart.of(fieldName, path, contentType)` 的 `contentType` 参数目前只能手写媒体类型字符串（例如 `"application/octet-stream"`），调用方容易写错、拼错或在多处重复硬编码同一个字符串字面量，且没有统一的、可枚举的常见媒体类型列表可供查阅。需要提供一个集中管理常见 multipart 文件媒体类型及其中文描述的枚举，方便调用方以 `MultipartContentTypeEnum.OCTET_STREAM.getContentType()` 的方式安全取值。

## What Changes

- 新增 `org.example.simple.http.MultipartContentTypeEnum` 枚举类，收录常见的 multipart 文件媒体类型常量（如八位字节流、纯文本、JSON、PDF、常见图片格式、ZIP 压缩包等），每个枚举值提供：
  - `getContentType()`：返回该媒体类型对应的标准 MIME 字符串（如 `"application/octet-stream"`）。
  - 中文描述（通过构造参数设置，供 Javadoc/日志等场景说明用途）。
- 不修改 `MultipartFilePart`/`MultipartFilePart.of()` 的方法签名：`contentType` 参数类型仍为 `String`，调用方可选择传入 `MultipartContentTypeEnum.XXX.getContentType()` 或自行提供的任意合法媒体类型字符串，两种方式都继续被 `MultipartFilePart.requireContentType` 校验。
- 补充 `MultipartFilePart` 相关 Javadoc 示例，说明可配合 `MultipartContentTypeEnum` 使用。

## Capabilities

### New Capabilities

- `multipart-content-type-enum`: 定义 `MultipartContentTypeEnum` 枚举收录的常见 multipart 文件媒体类型常量及其取值行为。

### Modified Capabilities

无（`http-client-utility` 中“支持多种请求体类型”的现有需求与场景不变：`contentType` 参数仍是任意合法媒体类型字符串，本变更只新增一种可选的取值来源，不改变现有契约或校验行为）。

## Impact

- 新增 `src/main/java/org/example/simple/http/MultipartContentTypeEnum.java`。
- 新增 `src/test/java/org/example/simple/http/MultipartContentTypeEnumTest.java`。
- 修改 `src/main/java/org/example/simple/http/MultipartFilePart.java` 的 Javadoc（新增使用枚举的示例说明），不修改其方法签名或校验逻辑。
- 不新增 Gradle 依赖；不影响 `HttpClients`、`FormRequestBody` 等现有出站请求构建逻辑。
