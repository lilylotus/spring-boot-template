# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/http-client-utility/spec.md`（delta）的明确确认。

## 2. 实现：MultipartStreamPart

- [x] 2.1 新增 `org.example.simple.http.MultipartStreamPart` 记录类型（`fieldName`、`inputStream`、`fileName`、`contentType`），规范构造器中：`fieldName`/`fileName` 用 `FormRequestBody.requireText` 校验非空白；`inputStream` 用 `Objects.requireNonNull` 校验非空；`contentType` 调用 `MultipartFilePart.requireContentType(contentType)` 校验。
- [x] 2.2 为 `MultipartStreamPart` 补充中文 Javadoc：说明这是基于输入流的 multipart 文件字段、传入后所有权转移给 `HttpClients`、`HttpClients` 会在请求发送完成后关闭该流、调用方不应再手动关闭或继续读取。

## 3. 实现：MultipartRequestBody 兼容扩展

- [x] 3.1 为 `MultipartRequestBody` 新增规范组件 `streamParts`（`List<MultipartStreamPart>`），规范构造器中用 `List.copyOf` 复制并对每个元素做 `Objects.requireNonNull` 校验（与现有 `fileParts` 处理方式一致）；"至少一个字段"校验扩展为 `textFields`、`fileParts`、`streamParts` 三者都为空时才抛出 `IllegalArgumentException`。
- [x] 3.2 为 `MultipartRequestBody` 新增两参数构造器 `MultipartRequestBody(Map<String, List<String>> textFields, List<MultipartFilePart> fileParts)`，内部委托调用三参数规范构造器并传入 `List.of()` 作为 `streamParts`，确保现有调用方源码不需要修改。

## 4. 实现：HttpClients 发送与流关闭

- [x] 4.1 修改 `HttpClients.ClientRuntime.createMultipartEntity`，对 `body.streamParts()` 中每个 `MultipartStreamPart` 调用 `builder.addBinaryBody(fieldName, inputStream, ContentType.parse(contentType), fileName)`，与现有 `fileParts` 处理逻辑并列。
- [x] 4.2 修改 `HttpClients.ClientRuntime.execute(HttpRequestData request)`：在构建 Apache 请求前，若请求体是 `MultipartRequestBody` 且 `streamParts()` 非空，收集这些流；用 `try { ... } finally { 逐个静默关闭收集到的流 }` 包裹现有的 `httpClient.execute(apacheRequest, ...)` 调用，确保成功、HTTP 失败状态、网络传输失败或超时场景下流都会被关闭恰好一次；关闭单个流抛出的 `IOException` 只记录/吞掉，不覆盖原始请求结果或原始异常。

## 5. 测试

- [x] 5.1 新增 `MultipartStreamPartTest`：校验 `fieldName`/`fileName` 为空白、`inputStream` 为 `null`、`contentType` 不合法时均抛出预期异常；合法参数能成功构造实例。
- [x] 5.2 在 `HttpModelsTest` 中新增/调整测试：两参数构造 `MultipartRequestBody` 时 `streamParts()` 为空列表；三参数构造且三者都为空时抛出 `IllegalArgumentException`；仅 `streamParts` 非空（文本和文件字段都为空）时能成功构造。
- [x] 5.3 在 `HttpClientsTest` 中新增端到端测试：使用内存 `ByteArrayInputStream` 构造 `MultipartStreamPart`，通过 `HttpClients.post` 发送 multipart 请求，断言服务端收到的字段名、文件名、媒体类型和字节内容与预期一致，且使用内置的、可观测"是否已关闭"的 `InputStream` 测试替身断言请求结束后该流已被关闭。
- [x] 5.4 在 `HttpClientsTest` 中新增测试：服务端返回非 `2xx` 状态（或连接失败）时，断言即使请求失败，传入的 `InputStream` 仍然被关闭。

## 6. 文档与验证

- [x] 6.1 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认全部测试通过且无新增编译或弃用警告。
- [x] 6.2 运行 `openspec validate add-multipart-stream-upload --strict`，核对代码、测试与 OpenSpec 文档一致，并在本文件补充验证记录。

## 验证记录

- `gradlew.bat test --tests "org.example.simple.http.MultipartStreamPartTest" --tests "org.example.simple.http.HttpModelsTest" --tests "org.example.simple.http.HttpClientsTest" --tests "org.example.simple.http.MultipartContentTypeEnumTest"`：全部通过。
- `gradlew.bat build`（全量）：`BUILD SUCCESSFUL`，无新增编译或弃用警告。
- `openspec validate add-multipart-stream-upload --strict`：`Change 'add-multipart-stream-upload' is valid`。
