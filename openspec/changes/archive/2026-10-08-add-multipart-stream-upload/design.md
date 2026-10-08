# Design

## Context

`MultipartFilePart`（`Path` 型文件来源）和 `HttpClients.ClientRuntime.createMultipartEntity` 的现状见 `proposal.md` 的 Why；`ClientRuntime.createMultipartEntity` 当前通过 `MultipartEntityBuilder.addBinaryBody(name, Path, ContentType, filename)` 添加文件字段。已确认 `httpclient5:5.6.4`（项目现有依赖，见 `build.gradle`）的 `MultipartEntityBuilder` 同时提供 `addBinaryBody(String, InputStream, ContentType, String)` 重载，因此不需要新增依赖即可支持基于流的文件字段。

`ClientRuntime.execute(HttpRequestData request)`（`HttpClients.java`）通过 `httpClient.execute(apacheRequest, responseHandler)` 同步发送请求：Apache HttpClient5 的经典（blocking）`execute` 在拿到响应之前会先把请求实体（包括 multipart 实体中基于 `InputStreamBody` 的分片）完整写出到连接；该调用返回（无论正常返回还是抛出 `IOException`/`HttpClientException`）时，调用方提供的输入流已经被读取过（成功时读完整，失败时可能只读了一部分）。这意味着"请求发送完成后关闭流"可以通过包裹 `httpClient.execute(...)` 调用的 `try/finally` 实现，不需要新增异步回调或装饰 `InputStream`。

## Goals / Non-Goals

**Goals:**
- `MultipartStreamPart` 提供的 `InputStream` 在 `HttpClients` 完成一次请求发送（成功、HTTP 失败状态、网络传输失败或超时）后，总能被关闭恰好一次，不依赖调用方自己处理。
- `MultipartRequestBody` 新增 `streamParts` 组件后，现有两参数构造方式 `new MultipartRequestBody(textFields, fileParts)` 继续可编译、可运行，不要求任何现有调用方修改代码。
- 复用 `MultipartFilePart` 已有的媒体类型校验逻辑（`requireContentType`），保证 `MultipartStreamPart` 的 `contentType` 校验行为与现有文件字段一致。

**Non-Goals:**
- 不支持"调用方提供可重复读取的流并由 `HttpClients` 自动重试"之类的重试语义；`InputStream` 本身通常不可重复读取，重试失败的请求超出本变更范围。
- 不改变基于 `Path` 的 `MultipartFilePart` 的任何现有行为、不新增"自动把流落地为临时文件"的回退路径。
- 不提供流式/分块上传进度回调。

## Decisions

- **新增类型 `MultipartStreamPart` 而不是修改 `MultipartFilePart` 本身**：`MultipartFilePart` 是 `record`，其规范构造器直接依赖 `Path` 做存在性/可读性校验（`Files.isRegularFile`/`Files.isReadable`），这套校验对 `InputStream` 没有意义（无法重复检查"是否可读"而不消费流）。拆成两个并列类型，让每种来源各自做适合自己的校验，避免在同一个类型里用可空字段模拟"互斥的两种来源"。
- **`MultipartRequestBody` 增加第三个规范组件 `streamParts`，同时保留一个两参数的非规范构造器做兼容桥接**：
  ```java
  public record MultipartRequestBody(
      Map<String, List<String>> textFields,
      List<MultipartFilePart> fileParts,
      List<MultipartStreamPart> streamParts) implements HttpRequestBody {

      public MultipartRequestBody(Map<String, List<String>> textFields, List<MultipartFilePart> fileParts) {
          this(textFields, fileParts, List.of());
      }
      // 规范构造器中的校验逻辑...
  }
  ```
  这样现有调用方（`HttpClientsTest`、`HttpModelsTest` 等）传两个参数的调用语句不需要任何修改即可继续通过编译；新调用方可以显式传入第三个参数使用流字段。考虑过"新增一个独立的 `MultipartStreamRequestBody` 类型"方案，但那样 `HttpClients`/`HttpRequestData` 的请求体处理逻辑要同时识别两种 multipart 请求体类型，并且一次请求如果同时需要文本、文件和流字段会很别扭；不采用。
  - “至少一个字段”的校验同步扩展为 `textFields.isEmpty() && fileParts.isEmpty() && streamParts.isEmpty()` 时才拒绝。
- **流的关闭时机：包裹 `ClientRuntime.execute` 中的 `httpClient.execute(...)` 调用**：在 `createApacheRequest`/`createEntity` 阶段收集本次请求用到的全部 `InputStream`（即 `MultipartRequestBody.streamParts()` 里的流），`execute()` 方法对 `httpClient.execute(...)` 的调用加一层 `try { ... } finally { streams.forEach(HttpClients::closeQuietly); }`，确保无论返回成功响应、抛出 `HttpClientException`（HTTP 失败状态）还是抛出 `IOException`/超时，流都会被关闭恰好一次。关闭失败（`IOException`）只记录/吞掉，不覆盖原始请求结果或原始异常——关闭资源失败不应该掩盖请求本身的成败。
  - 替代方案"用装饰器包裹 `InputStream`，在流读到 EOF 时自动关闭"更复杂且无法覆盖"请求失败、流没被读到 EOF"的场景，不采用。
- **`MultipartStreamPart` 的参数校验**：`fieldName`/`fileName` 复用 `FormRequestBody.requireText` 做非空白校验；`inputStream` 用 `Objects.requireNonNull`；`contentType` 直接复用 `MultipartFilePart.requireContentType(String)`（包内可见的静态方法，同包可直接调用），保证与 `MultipartFilePart` 的媒体类型校验规则完全一致。
- **`HttpClients.createMultipartEntity` 新增对 `streamParts` 的处理**：对每个 `MultipartStreamPart` 调用 `builder.addBinaryBody(fieldName, inputStream, ContentType.parse(contentType), fileName)`，与现有 `fileParts` 处理逻辑并列，不改变已有 `fileParts` 分支的代码路径。

## Risks / Trade-offs

- **调用方误以为流可以复用/继续读取**：传入 `MultipartStreamPart` 后流即被 `HttpClients` 消费并关闭，调用方如果在调用后继续操作该流会得到"流已关闭"的异常 → 缓解措施：`MultipartStreamPart` 的 Javadoc 明确写出所有权转移约定；这是本变更明确选择的契约（已与用户确认），作为既定设计记录在此，不在实现阶段重新讨论。
- **部分写出场景下流被"半读"后关闭**：如果网络传输在写请求体过程中失败，流可能只被读了一部分就被关闭，这是预期行为（失败后没有必要/没有办法继续读取或重试同一个流），不视为缺陷。
- **`MultipartRequestBody` 新增规范组件后，极少数通过反射直接访问 record 组件数量/顺序的代码会受影响** → 当前仓库内没有这类反射使用（已检索 `src/` 下没有对 `MultipartRequestBody` 做反射访问的代码），风险可接受。
