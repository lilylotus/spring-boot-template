# Design

## Context

`MultipartFilePart`（`src/main/java/org/example/simple/http/MultipartFilePart.java`）的 `contentType` 字段和 `of()` 工厂方法的参数类型都是 `String`，由 `requireContentType` 通过 `org.apache.hc.core5.http.ContentType.parse(...)` 校验是否符合 `type/subtype` 格式。本项目已依赖 `httpclient5:5.6.4`，其 `org.apache.hc.core5.http.ContentType` 类本身带有一批 `public static final ContentType` 常量（如 `ContentType.APPLICATION_OCTET_STREAM`），但这些常量面向通用 HTTP 内容协商场景（部分带 `charset`、覆盖范围与本项目需求不完全对应），且不是按“multipart 文件字段常用媒体类型”这一场景精选、也没有配套的中文描述。详见 `proposal.md` 的 Why。

项目中已有多个枚举（`HttpErrorType`、`RpcErrorCode`、`QrCodeErrorCorrection` 等）均放在业务所属包下，常量仅配中文 Javadoc 注释，目前没有“枚举常量自带 getter 字段”的先例，本变更是该模式在本项目中的第一次使用。

## Goals / Non-Goals

**Goals:**
- 新增的 `MultipartContentTypeEnum` 收录的每个常量都能通过 `getContentType()` 返回一个固定、明确的标准 MIME 字符串，且能直接通过 `MultipartFilePart.requireContentType` 的现有校验。
- 常量集合覆盖日常使用 `MultipartFilePart` 上传文件时的常见类别：通用二进制流、纯文本、JSON、常见图片格式、常见文档/压缩包格式。
- 不修改 `MultipartFilePart`/`MultipartFilePart.of()` 现有的公开签名和校验行为。

**Non-Goals:**
- 不做媒体类型的穷举（不收录所有 IANA 注册的 MIME 类型），只收录与"作为 multipart 文件字段"场景相关、项目后续大概率会用到的常见子集；未覆盖的类型调用方仍可直接传入字符串字面量。
- 不提供从文件扩展名/文件内容自动推断 `MultipartContentTypeEnum` 常量的能力（不做 MIME 类型嗅探），这是比"固定常量表"复杂得多的独立能力，超出本变更范围。
- 不废弃或替换 `MultipartFilePart.of()` 接受 `String contentType` 的现有重载；不新增要求调用方必须使用该枚举的重载或校验规则。

## Decisions

- **枚举值的 MIME 字符串直接以字符串字面量硬编码，不委托给 `org.apache.hc.core5.http.ContentType` 的同名常量**：`ContentType` 的部分常量默认带 `charset`（例如 `ContentType.TEXT_PLAIN.toString()` 包含 `; charset=UTF-8`），直接复用会让 `getContentType()` 的返回值在"纯 MIME type/subtype"和"带 charset 参数"之间不一致、也会让本枚举的行为随 `httpclient5` 版本升级而隐式变化。改为对每个常量显式写出标准 MIME 字符串（`type/subtype`，不含参数），行为自持且可预测，并仍能通过 `ContentType.parse(...)` 校验。
- **每个常量在构造时同时携带 MIME 字符串和中文描述两个字段，通过 `getContentType()`/对应 getter 暴露**：满足 `proposal.md` 中"以 `MultipartContentTypeEnum.OCTET_STREAM.getContentType()` 方式取值"的明确需求；中文描述用于 Javadoc 友好展示和未来日志/错误提示场景，不是单纯的注释，因此作为真实字段而非 Javadoc 注释存在。
- **不要求覆盖 `toString()`**：保持枚举默认的 `toString()`（即常量名）不变，避免与 `getContentType()` 的语义混淆；调用方需要 MIME 字符串时必须显式调用 `getContentType()`，不能依赖 `toString()` 的隐式转换。
- **常量集合范围**：收录 `OCTET_STREAM`（`application/octet-stream`，通用二进制流/未知类型，对应现状中最常见的硬编码场景）、`TEXT_PLAIN`（`text/plain`）、`JSON`（`application/json`）、`XML`（`application/xml`）、`PDF`（`application/pdf`）、`ZIP`（`application/zip`）、`JPEG`（`image/jpeg`）、`PNG`（`image/png`）、`GIF`（`image/gif`）。选取标准是：项目当前及可预见的文件上传场景（日志/文档归档、图片上传、通用二进制文件）会用到的类型，而不是复刻 `ContentType` 的完整列表。
- **放置位置为 `org.example.simple.http` 包**：与被服务的 `MultipartFilePart`/`FormRequestBody` 同包，符合项目现有"工具枚举与被服务的类同包"的组织方式（如 `HttpErrorType` 与 `HttpClients` 同包）。

## Risks / Trade-offs

- **常量集合不完整**：调用方需要的媒体类型未被收录时，仍需手写字符串字面量，枚举只能缓解而非根除"硬编码字符串"问题 → 缓解措施：Javadoc 中说明这是常见子集而非穷举，未覆盖类型可直接传入合法字符串，`MultipartFilePart.requireContentType` 的校验逻辑不变，不因此封闭扩展性。
- **未来需要新增常量时的兼容性**：枚举新增常量是兼容变更（不影响现有常量的名称或取值），但如果后续需要修正已发布常量的取值（例如发现某个 MIME 字符串不规范），会是破坏性变更 → 缓解措施：当前先只收录业界无歧义的标准 MIME 字符串（均可在 IANA 媒体类型注册表或常见 Web 标准中找到一致引用），降低后续订正的概率。
